package com.mrleonardos.codesides.split.fixture;

import com.mrleonardos.codesides.ServerSide;

@ServerSide
public final class ServerNumbers
{
	public static final int MAX_HANDLES = 256;

	public static final long BUDGET_BYTES = 4000000000L;

	private ServerNumbers()
	{
	}
}
