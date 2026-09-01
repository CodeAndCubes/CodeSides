package com.mrleonardos.codesides.split;

import static com.mrleonardos.codesides.split.Fixtures.PKG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.mrleonardos.codesides.Side;

class SideVerifierTest
{
	@Test
	void directCallToServerClassFromCommonCodeIsReported()
	{
		List<Violation> violations = verifyClient(Fixtures.subset("Api", "ServerImpl", "BadCommon", "GoodCommon"));

		assertEquals(1, violations.stream()
			.filter(v -> v.from.equals(PKG + "BadCommon") && v.target.contains("ServerImpl")).count(),
			"прямой вызов вырезанного класса — одно нарушение, а не по одному на инструкцию");
		assertFalse(violations.stream().anyMatch(v -> v.from.equals(PKG + "GoodCommon")),
			"обращение через общий интерфейс нарушением не является");
	}

	@Test
	void serverCallInsideCommonLambdaIsReported()
	{
		List<Violation> violations = verifyClient(Fixtures.subset("Api", "ServerImpl", "LambdaLeak"));

		assertTrue(violations.stream()
			.anyMatch(v -> v.from.equals(PKG + "LambdaLeak") && v.target.contains("ServerImpl")),
			"тело лямбды общего метода тоже проверяется");
	}

	@Test
	void catchOfServerOnlyExceptionIsReported()
	{
		List<Violation> violations = verifyClient(Fixtures.subset("ServerException", "CatchServer"));

		assertTrue(violations.stream()
			.anyMatch(v -> v.from.equals(PKG + "CatchServer") && v.target.contains("ServerException")),
			"тип в try/catch — такая же ссылка, как вызов");
	}

	@Test
	void abstractContractLeftWithoutImplementationIsReported()
	{
		List<Violation> violations = verifyClient(Fixtures.subset("Contract", "ContractImpl"));

		assertTrue(violations.stream().anyMatch(v -> v.from.equals(PKG + "ContractImpl")
			&& Violation.KIND_ABSTRACT.equals(v.kind) && v.target.contains("contractName")),
			"вырезанная реализация абстрактного метода оставила класс без контракта");
	}

	@Test
	void jdkContractLeftWithoutImplementationIsReported()
	{
		List<Violation> violations = verifyClient(Fixtures.subset("BridgeCase"));

		assertTrue(violations.stream().anyMatch(v -> v.from.equals(PKG + "BridgeCase")
			&& Violation.KIND_ABSTRACT.equals(v.kind) && v.target.contains("java.util.function.Supplier")),
			"контракт типа JDK проверяется без всякого classpath: иначе класс уехал бы падать AbstractMethodError");
	}

	@Test
	void inlinedConstantOfRemovedClassIsReported()
	{
		Map<String, byte[]> input = Fixtures.subset("ServerConstants", "ConstantUser");
		List<Violation> violations = verifyClient(input);

		assertTrue(violations.stream().anyMatch(v -> v.from.equals(PKG + "ConstantUser")
			&& Violation.KIND_INLINED.equals(v.kind) && v.target.contains("DB_PASSWORD")),
			"значение константы вырезанного класса физически осталось в клиентском наборе");
		assertTrue(Fixtures.containsText(SideSplitter.split(input, Side.CLIENT).classes,
			"codesides-db-password-secret"), "строка действительно лежит в байткоде: ровно поэтому сборка и падает");
		assertFalse(violations.stream().anyMatch(v -> v.target.contains("INTERNAL_TAG")),
			"private-константу вырезанного класса подставить некуда, совпадение текста нарушением не считается");
	}

	@Test
	void markedCompileTimeConstantIsReported()
	{
		List<Violation> violations = verifyClient(Fixtures.subset("MarkedConstant"));

		assertTrue(violations.stream().anyMatch(v -> v.from.equals(PKG + "MarkedConstant")
			&& Violation.KIND_CONSTANT.equals(v.kind) && v.target.contains("SERVER_SECRET")),
			"пометка стороной на константе времени компиляции не работает и должна называться нарушением");
		assertTrue(violations.stream().anyMatch(v -> v.from.equals(PKG + "MarkedConstant")
			&& Violation.KIND_INLINED.equals(v.kind)), "значение осталось в общем методе того же класса");
	}

	@Test
	void markedFieldWithInitializerIsReported()
	{
		Map<String, byte[]> input = Fixtures.subset("FieldInit");
		List<Violation> violations = verifyClient(input);

		assertTrue(violations.stream().anyMatch(v -> v.from.equals(PKG + "FieldInit")
			&& Violation.KIND_INITIALIZER.equals(v.kind) && v.target.contains("lambdaField")),
			"поле вырезано, а присваивание осталось в <init>: нарушение должно называть инициализатор");
		assertTrue(Fixtures.containsText(SideSplitter.split(input, Side.CLIENT).classes,
			"codesides-field-init-secret"),
			"тело лямбды держится за indy из <init> и остаётся: поэтому пометка поля с инициализатором и запрещена");
	}

	@Test
	void typeReferencesInsideAnnotationValuesAreReported()
	{
		List<Violation> violations = verifyClient(
			Fixtures.subset("AnnotatedCommon", "Uses", "Api", "ServerImpl", "ServerException"));

		assertTrue(violations.stream().anyMatch(v -> v.from.equals(PKG + "AnnotatedCommon") && v.fromMember == null
			&& v.target.equals(PKG + "ServerImpl")), "значение-массив в аннотации класса");
		assertTrue(violations.stream().anyMatch(v -> v.from.equals(PKG + "AnnotatedCommon")
			&& v.fromMember != null && v.fromMember.startsWith("nested") && v.target.equals(PKG + "ServerException")),
			"вложенная аннотация внутри значения-массива");
		assertTrue(violations.stream().anyMatch(v -> v.from.equals(PKG + "AnnotatedCommon")
			&& v.fromMember != null && v.fromMember.startsWith("parameter") && v.target.equals(PKG + "ServerImpl")),
			"аннотация параметра метода");
		assertTrue(violations.stream().anyMatch(v -> v.from.equals(PKG + "Uses")
			&& v.fromMember != null && v.fromMember.startsWith("fallback") && v.target.equals(PKG + "ServerImpl")),
			"значение по умолчанию члена аннотации");
	}

	@Test
	void cleanSetSplitsWithoutViolations()
	{
		String[] clean = { "Api", "ServerImpl", "ClientView", "GoodCommon", "Mixed", "Lambdas", "Boxed", "Flavours",
			"EnumUser", "Runner", "commonpkg/CommonThing", "commonpkg/package-info", "serverpkg/PackagedThing",
			"serverpkg/package-info", "serverpkg/deep/DeepThing" };

		assertTrue(verifyClient(Fixtures.subset(clean)).isEmpty(), "клиентская сторона чистого набора консистентна");
		assertTrue(SideVerifier.verify(SideSplitter.split(Fixtures.subset(clean), Side.SERVER), ClassLookup.EMPTY)
			.isEmpty(), "серверная сторона чистого набора консистентна");
	}

	private static List<Violation> verifyClient(Map<String, byte[]> input)
	{
		return SideVerifier.verify(SideSplitter.split(input, Side.CLIENT), ClassLookup.EMPTY);
	}
}
