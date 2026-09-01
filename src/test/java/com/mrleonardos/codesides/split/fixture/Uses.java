package com.mrleonardos.codesides.split.fixture;

public @interface Uses
{
	Class<?>[] value() default {};

	Class<?> fallback() default ServerImpl.class;

	Nested[] nested() default {};

	@interface Nested
	{
		Class<?>[] value() default {};
	}
}
