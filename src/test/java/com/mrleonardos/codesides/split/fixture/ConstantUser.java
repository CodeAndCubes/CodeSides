package com.mrleonardos.codesides.split.fixture;

public final class ConstantUser
{
	public String password()
	{
		return ServerConstants.DB_PASSWORD;
	}

	public String sameTextAsPrivateServerConstant()
	{
		return "codesides-internal-tag";
	}
}
