package com.mrleonardos.codesides.split.fixture;

import java.util.function.Supplier;

import com.mrleonardos.codesides.ServerSide;

public final class Lambdas
{
	@ServerSide
	public Supplier<String> serverSupplier()
	{
		return () -> "codesides-server-lambda-secret";
	}

	@ServerSide
	public Api serverAnonymous()
	{
		return new Api()
		{
			@Override
			public String describe()
			{
				return "codesides-server-anonymous-secret";
			}
		};
	}

	public String common()
	{
		return "lambdas-common";
	}
}
