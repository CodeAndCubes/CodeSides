package com.mrleonardos.codesides.split.fixture;

public final class GoodCommon
{
	private final Api api;

	public GoodCommon(Api api)
	{
		this.api = api;
	}

	public String describe()
	{
		return api.describe();
	}
}
