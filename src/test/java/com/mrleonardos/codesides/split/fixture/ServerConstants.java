package com.mrleonardos.codesides.split.fixture;

import com.mrleonardos.codesides.ServerSide;

@ServerSide
public final class ServerConstants
{
	public static final String DB_PASSWORD = "codesides-db-password-secret";

	private static final String INTERNAL_TAG = "codesides-internal-tag";

	private ServerConstants()
	{
	}

	public static String tag()
	{
		return INTERNAL_TAG;
	}
}
