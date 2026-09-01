package com.mrleonardos.codesides.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

class PackageGateTest
{
	@Test
	void gateNoticesAForbiddenReference() throws IOException
	{
		PackageGate gate = PackageGate.of(PackageGate.class);

		List<String> found = gate.scan(PackageGate.foreignSample());

		assertFalse(found.isEmpty(), "гейт обязан ловить ссылку на тип Minecraft");
		assertTrue(
			String.join("\n", found)
				.contains("EntityPlayer"),
			"в отчёте должен быть сам тип, а вышло " + found);
	}

	@Test
	void ownPackagesHoldNoPlatformTypes() throws IOException
	{
		PackageGate gate = PackageGate.of(PackageGate.class);

		assertEquals(java.util.Collections.emptyList(), gate.violations("com/mrleonardos/codesides/split"));
	}

	@Test
	void missingPackageIsNamedInTheFailure()
	{
		IOException failure = org.junit.jupiter.api.Assertions
			.assertThrows(IOException.class, () -> PackageGate.of(PackageGate.class)
				.violations("com/mrleonardos/codesides/nothing"));

		assertTrue(failure.getMessage()
			.contains("nothing"), "в сообщении должен быть пакет, а вышло " + failure.getMessage());
	}
}
