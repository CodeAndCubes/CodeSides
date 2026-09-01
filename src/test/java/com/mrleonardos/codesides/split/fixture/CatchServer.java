package com.mrleonardos.codesides.split.fixture;

public final class CatchServer
{
	public String risky()
	{
		try
		{
			return "ok";
		}
		catch (ServerException failure)
		{
			return "caught";
		}
	}
}
