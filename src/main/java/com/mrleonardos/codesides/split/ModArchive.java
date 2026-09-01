package com.mrleonardos.codesides.split;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Enumeration;
import java.util.GregorianCalendar;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Содержимое мода, разложенное на то, что режется, и то, что просто раскладывается по сторонам: классы,
 * ресурсы и манифест.
 *
 * <p>Источником может быть готовый jar (для 1.7.10 уже реобфусцированный) или каталоги сборки.
 * Записывается всегда jar с детерминированным порядком и фиксированным временем записей, чтобы сборка была
 * воспроизводимой.
 */
public final class ModArchive
{
	private static final String MANIFEST_PATH = "META-INF/MANIFEST.MF";
	private static final long ENTRY_TIME = new GregorianCalendar(1980, 1, 1, 0, 0, 0).getTimeInMillis();

	/** Классы: internal name → байткод. */
	public final Map<String, byte[]> classes;
	/** Всё остальное: путь внутри jar → содержимое. */
	public final Map<String, byte[]> resources;
	/** Манифест исходного jar либо {@code null}. */
	public final Manifest manifest;

	public ModArchive(Map<String, byte[]> classes, Map<String, byte[]> resources, Manifest manifest)
	{
		this.classes = classes;
		this.resources = resources;
		this.manifest = manifest;
	}

	/** Прочитать готовый jar. Подписи ({@code META-INF/*.SF} и ключи) отбрасываются: после разреза они мертвы. */
	public static ModArchive read(File jar) throws IOException
	{
		Map<String, byte[]> classes = new LinkedHashMap<>();
		Map<String, byte[]> resources = new LinkedHashMap<>();
		Manifest manifest = null;
		try (ZipFile zip = new ZipFile(jar))
		{
			for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements();)
			{
				ZipEntry entry = entries.nextElement();
				if (entry.isDirectory())
					continue;
				String name = entry.getName();
				try (InputStream in = zip.getInputStream(entry))
				{
					if (MANIFEST_PATH.equals(name))
						manifest = new Manifest(in);
					else if (isSignature(name))
						continue;
					else if (name.endsWith(".class"))
						classes.put(name.substring(0, name.length() - ".class".length()), ClasspathLookup.readAll(in));
					else
						resources.put(name, ClasspathLookup.readAll(in));
				}
			}
		}
		return new ModArchive(classes, resources, manifest);
	}

	/** Собрать содержимое из каталогов сборки: скомпилированные классы и обработанные ресурсы. */
	public static ModArchive fromDirectories(Collection<File> classDirs, Collection<File> resourceDirs,
		Manifest manifest) throws IOException
	{
		Map<String, byte[]> classes = new LinkedHashMap<>();
		Map<String, byte[]> resources = new LinkedHashMap<>();
		for (File root : classDirs)
			for (Map.Entry<String, byte[]> e : readTree(root).entrySet())
				if (e.getKey().endsWith(".class"))
					classes.put(e.getKey().substring(0, e.getKey().length() - ".class".length()), e.getValue());
		for (File root : resourceDirs)
			for (Map.Entry<String, byte[]> e : readTree(root).entrySet())
				if (!MANIFEST_PATH.equals(e.getKey()) && !isSignature(e.getKey()))
					resources.put(e.getKey(), e.getValue());
		return new ModArchive(classes, resources, manifest);
	}

	/** Записать jar из уже отобранных классов и ресурсов. */
	public static void write(File target, Map<String, byte[]> classes, Map<String, byte[]> resources,
		Manifest manifest) throws IOException
	{
		File parent = target.getParentFile();
		if (parent != null && !parent.isDirectory() && !parent.mkdirs())
			throw new IOException("не удалось создать каталог " + parent);

		Map<String, byte[]> entries = new TreeMap<>();
		for (Map.Entry<String, byte[]> e : classes.entrySet())
			entries.put(e.getKey() + ".class", e.getValue());
		for (Map.Entry<String, byte[]> e : resources.entrySet())
			if (!MANIFEST_PATH.equals(e.getKey()))
				entries.put(e.getKey(), e.getValue());

		OutputStream fileOut = new BufferedOutputStream(new FileOutputStream(target));
		try (JarOutputStream out = new JarOutputStream(fileOut))
		{
			Set<String> written = new LinkedHashSet<>();
			if (manifest != null)
			{
				ByteArrayOutputStream bytes = new ByteArrayOutputStream();
				withVersion(manifest).write(bytes);
				writeEntry(out, MANIFEST_PATH, bytes.toByteArray());
			}
			for (Map.Entry<String, byte[]> e : entries.entrySet())
			{
				writeDirectories(out, e.getKey(), written);
				writeEntry(out, e.getKey(), e.getValue());
			}
		}
	}

	private static void writeEntry(JarOutputStream out, String name, byte[] bytes) throws IOException
	{
		ZipEntry entry = new ZipEntry(name);
		entry.setTime(ENTRY_TIME);
		out.putNextEntry(entry);
		out.write(bytes);
		out.closeEntry();
	}

	private static Manifest withVersion(Manifest manifest)
	{
		Manifest copy = new Manifest(manifest);
		if (copy.getMainAttributes().getValue("Manifest-Version") == null)
			copy.getMainAttributes().putValue("Manifest-Version", "1.0");
		return copy;
	}

	private static void writeDirectories(JarOutputStream out, String path, Set<String> written) throws IOException
	{
		int slash = -1;
		while ((slash = path.indexOf('/', slash + 1)) >= 0)
		{
			String directory = path.substring(0, slash + 1);
			if (!written.add(directory))
				continue;
			ZipEntry entry = new ZipEntry(directory);
			entry.setTime(ENTRY_TIME);
			out.putNextEntry(entry);
			out.closeEntry();
		}
	}

	private static Map<String, byte[]> readTree(File root) throws IOException
	{
		Map<String, byte[]> files = new LinkedHashMap<>();
		if (!root.isDirectory())
			return files;
		Path base = root.toPath();
		try (Stream<Path> walk = Files.walk(base))
		{
			List<Path> found = walk.filter(Files::isRegularFile).sorted().collect(Collectors.toList());
			for (Path path : found)
				files.put(base.relativize(path).toString().replace(File.separatorChar, '/'), Files.readAllBytes(path));
		}
		return files;
	}

	private static boolean isSignature(String name)
	{
		if (!name.startsWith("META-INF/"))
			return false;
		String upper = name.toUpperCase(Locale.ROOT);
		return upper.endsWith(".SF") || upper.endsWith(".DSA") || upper.endsWith(".RSA") || upper.endsWith(".EC");
	}
}
