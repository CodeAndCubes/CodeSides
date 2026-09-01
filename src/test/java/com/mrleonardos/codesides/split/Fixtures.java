package com.mrleonardos.codesides.split;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

final class Fixtures
{
	static final String PKG = "com/mrleonardos/codesides/split/fixture/";

	private Fixtures()
	{
	}

	static Map<String, byte[]> all()
	{
		Path root = classpathRoot();
		Path fixtures = root.resolve(PKG);
		Map<String, byte[]> classes = new LinkedHashMap<>();
		try (Stream<Path> walk = Files.walk(fixtures))
		{
			List<Path> found = walk.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".class")).sorted()
				.collect(Collectors.toList());
			for (Path path : found)
			{
				String relative = root.relativize(path).toString().replace('\\', '/');
				classes.put(relative.substring(0, relative.length() - ".class".length()), Files.readAllBytes(path));
			}
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
		return classes;
	}

	static Map<String, byte[]> subset(String... simpleNames)
	{
		Set<String> wanted = new LinkedHashSet<>();
		for (String name : simpleNames)
			wanted.add(PKG + name);
		Map<String, byte[]> classes = new LinkedHashMap<>();
		for (Map.Entry<String, byte[]> e : all().entrySet())
		{
			String name = e.getKey();
			int dollar = name.indexOf('$');
			String outer = dollar < 0 ? name : name.substring(0, dollar);
			if (wanted.contains(name) || wanted.contains(outer))
				classes.put(name, e.getValue());
		}
		return classes;
	}

	static boolean containsText(Map<String, byte[]> classes, String text)
	{
		for (byte[] bytes : classes.values())
			if (new String(bytes, StandardCharsets.ISO_8859_1).contains(text))
				return true;
		return false;
	}

	private static Path classpathRoot()
	{
		try
		{
			return Paths.get(Fixtures.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		}
		catch (URISyntaxException e)
		{
			throw new IllegalStateException(e);
		}
	}
}
