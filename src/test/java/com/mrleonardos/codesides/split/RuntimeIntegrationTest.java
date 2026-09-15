package com.mrleonardos.codesides.split;

import static com.mrleonardos.codesides.split.Fixtures.PKG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import com.mrleonardos.codesides.Side;

class RuntimeIntegrationTest
{
	private static final String[] CLEAN = { "Api", "ServerImpl", "ClientView", "GoodCommon", "Mixed", "Lambdas",
		"Boxed", "Flavours", "EnumUser", "Runner", "commonpkg/CommonThing", "commonpkg/package-info",
		"serverpkg/PackagedThing", "serverpkg/package-info", "serverpkg/deep/DeepThing" };

	private static final String EXPECTED = "api-ok|common|2|sour|lambdas-common|-1|common-package";

	@Test
	void bothSidesLoadAndRunAfterSplit() throws Exception
	{
		Map<String, byte[]> input = Fixtures.subset(CLEAN);

		assertEquals(EXPECTED, runIn(SideSplitter.split(input, Side.CLIENT).classes),
			"клиентская сторона должна работать без серверных классов");
		assertEquals(EXPECTED, runIn(SideSplitter.split(input, Side.SERVER).classes),
			"серверная сторона должна работать без клиентских классов");
	}

	@Test
	void removedClassIsNotLoadableFromTheOtherSide()
	{
		Map<String, byte[]> input = Fixtures.subset(CLEAN);
		ByteClassLoader client = new ByteClassLoader(SideSplitter.split(input, Side.CLIENT).classes);

		assertThrows(ClassNotFoundException.class, () -> client.loadClass(PKG.replace('/', '.') + "ServerImpl"));
	}

	@Test
	void serializableLambdaStillDeserializesAfterSplit() throws Exception
	{
		Map<String, byte[]> input = Fixtures.subset("SerialLambda");

		assertEquals("serial-lambda", deserializeIn(SideSplitter.split(input, Side.CLIENT).classes, "SerialLambda", "make"),
			"десериализация лямбды идёт через $deserializeLambda$, и разрез не должен её ломать");
		assertEquals("serial-lambda", deserializeIn(SideSplitter.split(input, Side.SERVER).classes, "SerialLambda", "make"));
	}

	@Test
	void keptSerializableLambdaDeserializesAfterItsNeighbourIsCut() throws Exception
	{
		Map<String, byte[]> input = Fixtures.subset("SerialSecret");

		assertEquals("codesides-serial-common",
			deserializeIn(SideSplitter.split(input, Side.CLIENT).classes, "SerialSecret", "commonSerial"),
			"вырезание тела соседней лямбды не должно ломать $deserializeLambda$ общего метода");
	}

	private static String deserializeIn(Map<String, byte[]> classes, String holderName, String method) throws Exception
	{
		ByteClassLoader loader = new ByteClassLoader(classes);
		Class<?> holder = loader.loadClass(PKG.replace('/', '.') + holderName);
		Object lambda = holder.getMethod(method).invoke(holder.getDeclaredConstructor().newInstance());

		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ObjectOutputStream out = new ObjectOutputStream(bytes))
		{
			out.writeObject(lambda);
		}
		try (ObjectInputStream in = new LoaderInputStream(new ByteArrayInputStream(bytes.toByteArray()), loader))
		{
			return String.valueOf(((Supplier<?>) in.readObject()).get());
		}
	}

	private static String runIn(Map<String, byte[]> classes) throws Exception
	{
		ByteClassLoader loader = new ByteClassLoader(classes);
		Class<?> runner = loader.loadClass(PKG.replace('/', '.') + "Runner");
		Method run = runner.getMethod("run");
		return String.valueOf(run.invoke(null));
	}

	private static final class LoaderInputStream extends ObjectInputStream
	{
		private final ClassLoader loader;

		LoaderInputStream(InputStream in, ClassLoader loader) throws IOException
		{
			super(in);
			this.loader = loader;
		}

		@Override
		protected Class<?> resolveClass(ObjectStreamClass desc) throws ClassNotFoundException
		{
			return Class.forName(desc.getName(), false, loader);
		}
	}

	private static final class ByteClassLoader extends ClassLoader
	{
		private final Map<String, byte[]> classes;

		ByteClassLoader(Map<String, byte[]> classes)
		{
			super(null);
			this.classes = classes;
		}

		@Override
		protected Class<?> findClass(String name) throws ClassNotFoundException
		{
			byte[] bytes = classes.get(name.replace('.', '/'));
			if (bytes == null)
				throw new ClassNotFoundException(name);
			return defineClass(name, bytes, 0, bytes.length);
		}
	}
}
