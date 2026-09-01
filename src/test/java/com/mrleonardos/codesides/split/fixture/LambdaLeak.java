package com.mrleonardos.codesides.split.fixture;

import java.util.function.IntSupplier;

public final class LambdaLeak
{
	public IntSupplier leak()
	{
		return () -> ServerImpl.secret();
	}
}
