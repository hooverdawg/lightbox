package net.hooverdawg.lightbox;

import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Lightbox {
	public static final String MOD_ID = "lightbox";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);


	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
