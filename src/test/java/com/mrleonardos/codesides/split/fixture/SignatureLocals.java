package com.mrleonardos.codesides.split.fixture;

import java.util.List;

import com.mrleonardos.codesides.ServerSide;

public final class SignatureLocals
{
	@ServerSide
	public static final class Hidden
	{
	}

	public int localGeneric()
	{
		List<Hidden> local = null;
		return local == null ? 0 : local.size();
	}
}
