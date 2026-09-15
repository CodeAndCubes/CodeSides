package com.mrleonardos.codesides.split.fixture;

import java.io.Serializable;
import java.util.function.Supplier;

import com.mrleonardos.codesides.ServerSide;

public final class SerialSecret
{
	@ServerSide
	public Supplier<String> serverSerializable()
	{
		return (Supplier<String> & Serializable) () -> "codesides-ser-lambda-secret";
	}

	@ServerSide
	public Supplier<String> serverPlain()
	{
		return () -> "codesides-plain-lambda-secret";
	}

	public Supplier<String> commonSerial()
	{
		return (Supplier<String> & Serializable) () -> "codesides-serial-common";
	}
}
