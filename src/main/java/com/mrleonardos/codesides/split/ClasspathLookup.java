package com.mrleonardos.codesides.split;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Ищет байткод внешних классов в classpath сборки: jar'ы Minecraft, Forge и библиотек плюс каталоги классов.
 * Читает лениво и кэширует, поэтому дешёв даже на classpath целого модпака.
 *
 * <p>Держит jar'ы открытыми, поэтому после верификации нужен {@link #close()}.
 */
public final class ClasspathLookup implements ClassLookup, Closeable
{
	private final List<ZipFile> archives = new ArrayList<>();
	private final List<File> directories = new ArrayList<>();
	private final Map<String, byte[]> cache = new HashMap<>();

	/** Открыть classpath: элементы-архивы читаются как zip, элементы-каталоги как дерево .class файлов. */
	public ClasspathLookup(Collection<File> entries)
	{
		for (File entry : entries)
		{
			if (entry.isDirectory())
			{
				directories.add(entry);
				continue;
			}
			if (!entry.isFile())
				continue;
			try
			{
				archives.add(new ZipFile(entry));
			}
			catch (IOException ignored)
			{
				continue;
			}
		}
	}

	@Override
	public byte[] find(String internalName)
	{
		if (cache.containsKey(internalName))
			return cache.get(internalName);
		byte[] bytes = load(internalName + ".class");
		cache.put(internalName, bytes);
		return bytes;
	}

	@Override
	public void close()
	{
		for (ZipFile archive : archives)
		{
			try
			{
				archive.close();
			}
			catch (IOException ignored)
			{
				continue;
			}
		}
		archives.clear();
	}

	private byte[] load(String path)
	{
		for (File directory : directories)
		{
			File candidate = new File(directory, path);
			if (!candidate.isFile())
				continue;
			try
			{
				return Files.readAllBytes(candidate.toPath());
			}
			catch (IOException ignored)
			{
				continue;
			}
		}
		for (ZipFile archive : archives)
		{
			ZipEntry entry = archive.getEntry(path);
			if (entry == null)
				continue;
			try (InputStream in = archive.getInputStream(entry))
			{
				return readAll(in);
			}
			catch (IOException ignored)
			{
				continue;
			}
		}
		return null;
	}

	static byte[] readAll(InputStream in) throws IOException
	{
		byte[] buffer = new byte[8192];
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		int read;
		while ((read = in.read(buffer)) != -1)
			out.write(buffer, 0, read);
		return out.toByteArray();
	}
}
