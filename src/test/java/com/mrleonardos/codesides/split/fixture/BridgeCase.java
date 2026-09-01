package com.mrleonardos.codesides.split.fixture;

import java.util.function.Supplier;

import com.mrleonardos.codesides.ServerSide;

public final class BridgeCase implements Supplier<String>
{
	@ServerSide
	@Override
	public String get()
	{
		return "codesides-bridge-server-value";
	}
}
