package com.mrleonardos.codesides.split.fixture;

import com.mrleonardos.codesides.ServerSide;

@ServerSide
public final class ServerImpl implements Api
{
	@Override
	public String describe()
	{
		return "server";
	}

	public static int secret()
	{
		return 42;
	}
}
