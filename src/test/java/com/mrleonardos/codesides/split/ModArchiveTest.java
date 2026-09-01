package com.mrleonardos.codesides.split;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.mrleonardos.codesides.Side;

class ModArchiveTest
{
	@TempDir
	Path directory;

	@Test
	void writtenArchiveReadsBackIdentically() throws IOException
	{
		Map<String, byte[]> classes = Fixtures.subset("Api", "GoodCommon");
		Map<String, byte[]> resources = new LinkedHashMap<>();
		resources.put("mcmod.info", "[]".getBytes(StandardCharsets.UTF_8));
		resources.put("assets/mymod/lang/ru_RU.lang", "item.x.name=Штука".getBytes(StandardCharsets.UTF_8));
		resources.put("META-INF/services/com.example.Provider", "com.example.Impl".getBytes(StandardCharsets.UTF_8));

		Manifest manifest = new Manifest();
		manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
		manifest.getMainAttributes().putValue("FMLCorePlugin", "com.example.Core");

		File target = directory.resolve("mymod-server.jar").toFile();
		ModArchive.write(target, classes, resources, manifest);

		ModArchive read = ModArchive.read(target);
		assertEquals(classes.keySet(), read.classes.keySet());
		for (Map.Entry<String, byte[]> e : classes.entrySet())
			assertArrayEquals(e.getValue(), read.classes.get(e.getKey()));
		assertEquals(resources.keySet(), read.resources.keySet());
		assertFalse(read.resources.containsKey("META-INF/MANIFEST.MF"), "манифест не должен попадать в ресурсы");
		assertEquals("com.example.Core", read.manifest.getMainAttributes().getValue("FMLCorePlugin"));
	}

	@Test
	void writingTwiceProducesTheSameBytes() throws IOException
	{
		Map<String, byte[]> classes = Fixtures.subset("Api");
		Map<String, byte[]> resources = new LinkedHashMap<>();
		resources.put("mcmod.info", "[]".getBytes(StandardCharsets.UTF_8));

		File first = directory.resolve("first.jar").toFile();
		File second = directory.resolve("second.jar").toFile();
		ModArchive.write(first, classes, resources, null);
		ModArchive.write(second, classes, resources, null);

		assertArrayEquals(Files.readAllBytes(first.toPath()), Files.readAllBytes(second.toPath()),
			"сборка должна быть воспроизводимой");
	}

	@Test
	void writingTwiceWithManifestProducesTheSameBytes() throws IOException
	{
		Map<String, byte[]> classes = Fixtures.subset("Api");
		Map<String, byte[]> resources = new LinkedHashMap<>();
		resources.put("mcmod.info", "[]".getBytes(StandardCharsets.UTF_8));

		Manifest manifest = new Manifest();
		manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
		manifest.getMainAttributes().putValue("FMLCorePlugin", "com.example.Core");

		File first = directory.resolve("first.jar").toFile();
		File second = directory.resolve("second.jar").toFile();
		ModArchive.write(first, classes, resources, manifest);
		ModArchive.write(second, classes, resources, manifest);

		assertArrayEquals(Files.readAllBytes(first.toPath()), Files.readAllBytes(second.toPath()),
			"сборка должна быть воспроизводимой");

		Map<String, byte[]> manifestBytes = new LinkedHashMap<>();
		Map<String, Long> manifestTimes = new LinkedHashMap<>();
		Map<String, Long> resourceTimes = new LinkedHashMap<>();
		for (File jar : Arrays.asList(first, second))
		{
			try (ZipFile zip = new ZipFile(jar))
			{
				ZipEntry entry = zip.getEntry("META-INF/MANIFEST.MF");
				assertNotNull(entry, "манифест должен попадать в архив");
				try (InputStream in = zip.getInputStream(entry))
				{
					manifestBytes.put(jar.getName(), ClasspathLookup.readAll(in));
				}
				manifestTimes.put(jar.getName(), entry.getTime());
				ZipEntry resource = zip.getEntry("mcmod.info");
				assertNotNull(resource, "ресурс должен попадать в архив");
				resourceTimes.put(jar.getName(), resource.getTime());
			}
		}

		assertArrayEquals(manifestBytes.get("first.jar"), manifestBytes.get("second.jar"),
			"запись манифеста должна быть воспроизводимой");
		assertEquals(manifestTimes.get("first.jar"), manifestTimes.get("second.jar"),
			"время записи манифеста должно быть фиксированным");
		assertEquals(manifestTimes, resourceTimes, "манифест должен получать то же время, что и остальные записи");
	}

	@Test
	void writtenManifestKeepsItsAttributes() throws IOException
	{
		Map<String, byte[]> classes = Fixtures.subset("Api");
		Map<String, byte[]> resources = new LinkedHashMap<>();
		resources.put("mcmod.info", "[]".getBytes(StandardCharsets.UTF_8));

		Manifest manifest = new Manifest();
		manifest.getMainAttributes().putValue("FMLCorePlugin", "com.example.Core");

		File target = directory.resolve("mymod-server.jar").toFile();
		ModArchive.write(target, classes, resources, manifest);

		try (ZipFile zip = new ZipFile(target))
		{
			ZipEntry entry = zip.getEntry("META-INF/MANIFEST.MF");
			assertNotNull(entry, "манифест должен попадать в архив");
			assertEquals("META-INF/MANIFEST.MF", zip.entries().nextElement().getName(),
				"манифест должен идти первой записью");
			Manifest written;
			try (InputStream in = zip.getInputStream(entry))
			{
				written = new Manifest(in);
			}
			Map<String, String> attributes = new LinkedHashMap<>();
			for (Map.Entry<Object, Object> attribute : written.getMainAttributes().entrySet())
				attributes.put(String.valueOf(attribute.getKey()), String.valueOf(attribute.getValue()));
			Map<String, String> expected = new LinkedHashMap<>();
			expected.put("Manifest-Version", "1.0");
			expected.put("FMLCorePlugin", "com.example.Core");
			assertEquals(expected, attributes);
		}
	}

	@Test
	void resourcesAreSplitByRules() throws IOException
	{
		ResourceRules rules = ResourceRules.of(Arrays.asList("assets/mymod/secret/**"),
			Collections.emptyList(), true);
		Map<String, byte[]> resources = new LinkedHashMap<>();
		resources.put("assets/mymod/textures/gui.png", new byte[] { 1 });
		resources.put("assets/mymod/secret/economy.json", new byte[] { 2 });
		resources.put("mcmod.info", new byte[] { 3 });

		Map<String, byte[]> forClient = new LinkedHashMap<>();
		Map<String, byte[]> forServer = new LinkedHashMap<>();
		for (Map.Entry<String, byte[]> e : resources.entrySet())
		{
			if (rules.keep(e.getKey(), Side.CLIENT))
				forClient.put(e.getKey(), e.getValue());
			if (rules.keep(e.getKey(), Side.SERVER))
				forServer.put(e.getKey(), e.getValue());
		}

		assertTrue(forClient.containsKey("assets/mymod/textures/gui.png"));
		assertFalse(forClient.containsKey("assets/mymod/secret/economy.json"));
		assertFalse(forServer.containsKey("assets/mymod/textures/gui.png"));
		assertTrue(forServer.containsKey("assets/mymod/secret/economy.json"));
		assertTrue(forClient.containsKey("mcmod.info") && forServer.containsKey("mcmod.info"));
	}
}
