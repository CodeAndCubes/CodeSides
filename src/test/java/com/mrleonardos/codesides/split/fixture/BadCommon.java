package com.mrleonardos.codesides.split.fixture;

public final class BadCommon
{
	public int leak()
	{
		return ServerImpl.secret();
	}
}
