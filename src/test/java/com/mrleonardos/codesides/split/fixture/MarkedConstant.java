package com.mrleonardos.codesides.split.fixture;

import com.mrleonardos.codesides.ServerSide;

public final class MarkedConstant
{
	@ServerSide
	public static final String SERVER_SECRET = "codesides-marked-constant-secret";

	public String read()
	{
		return SERVER_SECRET;
	}
}
