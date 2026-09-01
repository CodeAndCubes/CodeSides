package com.mrleonardos.codesides.split;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Байткод классов платформы: {@code java.*}, {@code javax.*}, {@code jdk.*}, {@code sun.*}. Берётся из
 * образа той JVM, в которой идёт сборка, потому что compile classpath мода платформу не содержит.
 *
 * <p>Без него контракты вроде {@code Supplier}, {@code Comparable} или {@code Runnable} не проверялись бы
 * вовсе: нерезолвнутый супертип верификатор трактует консервативно, как «реализация есть», и класс с
 * вырезанной единственной реализацией уезжал бы игроку падать {@code AbstractMethodError}.
 */
public final class PlatformLookup implements ClassLookup
{
	/** Общий экземпляр: кэш байткода платформы живёт столько же, сколько процесс сборки. */
	public static final PlatformLookup INSTANCE = new PlatformLookup();

	private static final String[] PREFIXES = { "java/", "javax/", "jdk/", "sun/", "com/sun/", "org/w3c/dom/",
		"org/xml/sax/" };

	private final Map<String, byte[]> cache = new HashMap<>();

	private PlatformLookup()
	{
	}

	@Override
	public synchronized byte[] find(String internalName)
	{
		if (internalName == null || !isPlatform(internalName))
			return null;
		if (cache.containsKey(internalName))
			return cache.get(internalName);
		byte[] bytes = load(internalName + ".class");
		cache.put(internalName, bytes);
		return bytes;
	}

	private static boolean isPlatform(String internalName)
	{
		for (String prefix : PREFIXES)
			if (internalName.startsWith(prefix))
				return true;
		return false;
	}

	private static byte[] load(String path)
	{
		byte[] bytes = read(ClassLoader.getSystemResourceAsStream(path));
		if (bytes != null)
			return bytes;
		ClassLoader own = PlatformLookup.class.getClassLoader();
		return own == null ? null : read(own.getResourceAsStream(path));
	}

	private static byte[] read(InputStream in)
	{
		if (in == null)
			return null;
		try
		{
			return ClasspathLookup.readAll(in);
		}
		catch (IOException failed)
		{
			return null;
		}
		finally
		{
			close(in);
		}
	}

	private static void close(InputStream in)
	{
		try
		{
			in.close();
		}
		catch (IOException ignored)
		{
			return;
		}
	}
}
