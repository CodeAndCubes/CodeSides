package com.mrleonardos.codesides.split;

import static com.mrleonardos.codesides.split.Fixtures.PKG;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import com.mrleonardos.codesides.Side;

class SideSplitterTest
{
	private static final String SERVER_IMPL = PKG + "ServerImpl";
	private static final String CLIENT_VIEW = PKG + "ClientView";
	private static final String MIXED = PKG + "Mixed";
	private static final String LAMBDAS = PKG + "Lambdas";
	private static final String BOXED = PKG + "Boxed";
	private static final String FLAVOURS = PKG + "Flavours";
	private static final String SERIAL_LAMBDA = PKG + "SerialLambda";

	@Test
	void clientSideDropsServerClassesMembersAndPackages()
	{
		SplitOutput client = SideSplitter.split(Fixtures.all(), Side.CLIENT);

		assertTrue(client.removedClasses.contains(SERVER_IMPL), "класс с @ServerSide уходит из клиента");
		assertTrue(client.removedClasses.contains(PKG + "serverpkg/PackagedThing"), "пакет помечен через package-info");
		assertTrue(client.removedClasses.contains(PKG + "serverpkg/deep/DeepThing"), "пометка пакета каскадит вглубь");
		assertFalse(client.classes.containsKey(SERVER_IMPL));
		assertFalse(client.classes.containsKey(PKG + "serverpkg/package-info"), "маркер серверного пакета не нужен клиенту");

		assertTrue(client.classes.containsKey(CLIENT_VIEW), "клиентский класс остаётся");
		assertTrue(client.classes.containsKey(PKG + "commonpkg/CommonThing"));
		assertTrue(client.classes.containsKey(PKG + "commonpkg/package-info"), "package-info общего пакета сохраняется");

		assertTrue(client.removedFields.contains(new MemberRef(MIXED, "serverField", "I")));
		assertTrue(client.removedMethods.contains(new MemberRef(MIXED, "serverOnly", "()I")));
		assertFalse(client.removedMethods.contains(new MemberRef(MIXED, "common", "()Ljava/lang/String;")));
	}

	@Test
	void serverSideDropsClientCode()
	{
		SplitOutput server = SideSplitter.split(Fixtures.all(), Side.SERVER);

		assertTrue(server.removedClasses.contains(CLIENT_VIEW));
		assertTrue(server.classes.containsKey(SERVER_IMPL));
		assertTrue(server.classes.containsKey(PKG + "serverpkg/PackagedThing"));
		assertTrue(server.removedMethods.contains(new MemberRef(MIXED, "clientOnly", "()Ljava/lang/String;")));
		assertTrue(server.removedFields.contains(new MemberRef(MIXED, "clientField", "Ljava/lang/String;")));
	}

	@Test
	void lambdaAndAnonymousBodiesFollowTheirMethod()
	{
		SplitOutput client = SideSplitter.split(Fixtures.all(), Side.CLIENT);

		assertTrue(client.removedClasses.contains(LAMBDAS + "$1"), "анонимный класс серверного метода вырезан");
		assertTrue(client.removedMethods.stream().anyMatch(m -> m.owner.equals(LAMBDAS) && m.name.startsWith("lambda$")),
			"тело лямбды серверного метода вырезано");
		assertFalse(Fixtures.containsText(client.classes, "codesides-server-lambda-secret"),
			"строка из серверной лямбды не должна попасть в клиентский jar");
		assertFalse(Fixtures.containsText(client.classes, "codesides-server-anonymous-secret"),
			"строка из серверной анонимки не должна попасть в клиентский jar");
		assertTrue(client.classes.containsKey(LAMBDAS), "сам класс общий и остаётся");

		SplitOutput server = SideSplitter.split(Fixtures.all(), Side.SERVER);
		assertTrue(Fixtures.containsText(server.classes, "codesides-server-lambda-secret"),
			"на своей стороне лямбда остаётся целой");
		assertTrue(server.classes.containsKey(LAMBDAS + "$1"));
	}

	@Test
	void compilerGeneratedMembersOfCommonCodeSurvive()
	{
		SplitOutput client = SideSplitter.split(Fixtures.all(), Side.CLIENT);

		ClassNode boxed = read(client.classes.get(BOXED));
		assertTrue(hasMethod(boxed, "compareTo", "(Ljava/lang/Object;)I"), "мост generic-override нельзя удалять");

		ClassNode flavours = read(client.classes.get(FLAVOURS));
		assertTrue(hasField(flavours, "$VALUES"), "служебное поле enum достижимо из <clinit> и остаётся");
		assertTrue(client.classes.keySet().stream().anyMatch(name -> name.startsWith(PKG + "EnumUser$")),
			"switch-map класс общего кода остаётся");
	}

	@Test
	void serializableLambdaHelperIsNeverSwept()
	{
		Map<String, byte[]> input = Fixtures.subset("SerialLambda");
		String desc = "(Ljava/lang/invoke/SerializedLambda;)Ljava/lang/Object;";

		for (Side side : Side.values())
		{
			ClassNode holder = read(SideSplitter.split(input, side).classes.get(SERIAL_LAMBDA));
			assertTrue(hasMethod(holder, "$deserializeLambda$", desc),
				"JVM зовёт $deserializeLambda$ по имени, ссылок в байткоде нет: сметать его нельзя, сторона " + side);
		}
	}

	@Test
	void sideAnnotationsAreErasedFromOutput()
	{
		SplitOutput server = SideSplitter.split(Fixtures.all(), Side.SERVER);

		assertFalse(Fixtures.containsText(server.classes, "com/mrleonardos/codesides/ServerSide"));
		assertFalse(Fixtures.containsText(server.classes, "com/mrleonardos/codesides/ClientSide"));
	}

	@Test
	void removedClassNamesDisappearFromRemainingBytecode()
	{
		Map<String, byte[]> input = Fixtures.subset("Api", "ServerImpl", "GoodCommon", "Mixed");
		SplitOutput client = SideSplitter.split(input, Side.CLIENT);

		assertFalse(Fixtures.containsText(client.classes, "ServerImpl"),
			"имя вырезанного класса не должно остаться даже в константах");
	}

	private static boolean hasMethod(ClassNode cn, String name, String desc)
	{
		for (MethodNode mn : cn.methods)
			if (mn.name.equals(name) && mn.desc.equals(desc))
				return true;
		return false;
	}

	private static boolean hasField(ClassNode cn, String name)
	{
		for (FieldNode fn : cn.fields)
			if (fn.name.equals(name))
				return true;
		return false;
	}

	private static ClassNode read(byte[] bytes)
	{
		ClassNode cn = new ClassNode(Opcodes.ASM9);
		new ClassReader(bytes).accept(cn, 0);
		return cn;
	}
}
