package com.mrleonardos.codesides.split.fixture;

import com.mrleonardos.codesides.ServerSide;

public final class ContractImpl implements Contract
{
	@ServerSide
	@Override
	public String contractName()
	{
		return "contract-impl";
	}
}
