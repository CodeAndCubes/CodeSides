package com.mrleonardos.codesides.split.fixture;

import java.io.Serializable;
import java.util.function.Supplier;

public final class SerialLambda
{
	public Supplier<String> make()
	{
		return (Supplier<String> & Serializable) () -> "serial-lambda";
	}
}
