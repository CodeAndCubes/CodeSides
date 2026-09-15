package com.mrleonardos.codesides.split;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Test;

import com.mrleonardos.codesides.Side;

class ResourceRulesTest
{
	@Test
	void defaultsKeepClientMediaOutOfServerJar()
	{
		ResourceRules rules = ResourceRules.of(Collections.emptyList(), Collections.emptyList(), true);

		assertEquals(Side.CLIENT, rules.sideOf("assets/mymod/textures/blocks/machine.png"));
		assertEquals(Side.CLIENT, rules.sideOf("assets/mymod/sounds.json"));
		assertEquals(Side.CLIENT, rules.sideOf("assets/textures/gui.png"));
		assertFalse(rules.keep("assets/mymod/textures/blocks/machine.png", Side.SERVER));
		assertTrue(rules.keep("assets/mymod/textures/blocks/machine.png", Side.CLIENT));
	}

	@Test
	void languageFilesAndModMetadataStayCommon()
	{
		ResourceRules rules = ResourceRules.of(Collections.emptyList(), Collections.emptyList(), true);

		assertNull(rules.sideOf("assets/mymod/lang/ru_RU.lang"), "сервер тоже переводит строки");
		assertNull(rules.sideOf("mcmod.info"));
		assertNull(rules.sideOf("assets/mymod/recipes/alloy.json"));
		assertTrue(rules.keep("assets/mymod/lang/ru_RU.lang", Side.SERVER));
		assertTrue(rules.keep("assets/mymod/lang/ru_RU.lang", Side.CLIENT));
	}

	@Test
	void explicitMasksDecideTheSide()
	{
		ResourceRules rules = ResourceRules.of(Arrays.asList("assets/mymod/secret/**"),
			Arrays.asList("assets/mymod/gui/*.json"), false);

		assertEquals(Side.SERVER, rules.sideOf("assets/mymod/secret/economy.json"));
		assertEquals(Side.CLIENT, rules.sideOf("assets/mymod/gui/layout.json"));
		assertNull(rules.sideOf("assets/mymod/gui/nested/layout.json"), "одна звезда не переходит через /");
		assertNull(rules.sideOf("assets/mymod/textures/x.png"), "без масок по умолчанию текстуры общие");
	}

	@Test
	void leadingDoubleStarMatchesRootFile()
	{
		ResourceRules rules = ResourceRules.of(Arrays.asList("**/secret.dat"), Collections.emptyList(), false);

		assertEquals(Side.SERVER, rules.sideOf("secret.dat"), "**/foo ловит и файл в корне архива");
		assertEquals(Side.SERVER, rules.sideOf("assets/mymod/secret.dat"), "**/foo ловит файл в любом каталоге");
		assertNull(rules.sideOf("assets/mymod/secret.data"), "маска не цепляет похожие имена");
	}

	@Test
	void specialCharactersInMasksAreLiteral()
	{
		ResourceRules rules = ResourceRules.of(Arrays.asList("assets/my-mod/data.json", "config/*.cfg+backup"),
			Collections.emptyList(), false);

		assertEquals(Side.SERVER, rules.sideOf("assets/my-mod/data.json"));
		assertEquals(Side.SERVER, rules.sideOf("config/mymod.cfg+backup"));
		assertNull(rules.sideOf("assets/myXmod/data.json"), "точка и дефис — обычные символы, не regex");
	}

	@Test
	void conflictingMasksAreRejected()
	{
		ResourceRules rules = ResourceRules.of(Arrays.asList("assets/**/config.json"),
			Arrays.asList("assets/mymod/**"), false);

		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
			() -> rules.sideOf("assets/mymod/config.json"));
		assertTrue(error.getMessage().contains("assets/mymod/config.json"));
	}
}
