package com.mrleonardos.codesides.split.fixture;

import com.mrleonardos.codesides.ClientSide;
import com.mrleonardos.codesides.ServerSide;

public class Mixed
{
	@ServerSide
	public int serverField;

	@ClientSide
	public String clientField;

	@ServerSide
	public int serverOnly()
	{
		return serverField;
	}

	@ClientSide
	public String clientOnly()
	{
		return clientField;
	}

	public String common()
	{
		return "common";
	}
}
