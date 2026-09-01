package com.mrleonardos.codesides.split.fixture;

@Uses({ ServerImpl.class })
public final class AnnotatedCommon
{
	@Uses(nested = @Uses.Nested({ ServerException.class }))
	public String nested()
	{
		return "annotated-nested";
	}

	public String parameter(@Uses({ ServerImpl.class }) String value)
	{
		return value;
	}
}
