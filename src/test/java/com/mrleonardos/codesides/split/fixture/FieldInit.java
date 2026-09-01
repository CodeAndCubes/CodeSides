package com.mrleonardos.codesides.split.fixture;

import java.util.function.Supplier;

import com.mrleonardos.codesides.ServerSide;

public final class FieldInit
{
	@ServerSide
	public Supplier<String> lambdaField = () -> "codesides-field-init-secret";

	public String common()
	{
		return "field-init-common";
	}
}
