package com.mrleonardos.codesides;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Помеченный элемент существует ТОЛЬКО в клиентском jar и физически вырезается из серверного на сборке.
 * Зеркало {@link ServerSide}: применяется точечно, непомеченное считается общим (common).
 *
 * <p>Обычно метятся рендер и GUI (всё, что обращается к {@code net.minecraft.client.*}), чтобы
 * серверный jar не тянул клиентские классы Minecraft.
 * Куда можно ставить и как работает контракт: см. {@link ServerSide}.
 *
 * @see ServerSide
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.FIELD, ElementType.PACKAGE })
public @interface ClientSide
{
}
