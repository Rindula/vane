package org.oddlama.vane.core.config;

import static org.reflections.ReflectionUtils.*;

import org.apache.commons.lang.ArrayUtils;
import java.lang.StringBuilder;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.bukkit.configuration.file.YamlConfiguration;

import org.oddlama.vane.annotation.config.ConfigDoubleList;
import org.oddlama.vane.core.YamlLoadException;

public class ConfigDoubleListField extends ConfigField<Map<String, List<String>>> {
	public ConfigDoubleList annotation;

	public ConfigDoubleListField(Object owner, Field field, Function<String, String> map_name, ConfigDoubleList annotation) {
		super(owner, field, map_name, "double list");
		this.annotation = annotation;
	}

	private void append_double_list_defintion(StringBuilder builder, String indent, String prefix) {
		append_list_definition(builder, indent, prefix, ArrayUtils.toObject(annotation.def()), (b, d) -> b.append(d));
	}

	@Override
	public void generate_yaml(StringBuilder builder, String indent) {
		append_description(builder, indent, annotation.desc());
		append_value_range(builder, indent, annotation.min(), annotation.max(), Double.NaN, Double.NaN);

		// Default
		builder.append(indent);
		builder.append("# Default:\n");
		append_double_list_defintion(builder, indent, "# ");

		// Definition
		builder.append(indent);
		builder.append(basename());
		builder.append(":\n");
		append_double_list_defintion(builder, indent, "");
	}

	@Override
	public void check_loadable(YamlConfiguration yaml) throws YamlLoadException {
		check_yaml_path(yaml);

		if (!yaml.isList(yaml_path())) {
			throw new YamlLoadException("Invalid type for yaml path '" + yaml_path() + "', expected list");
		}

		for (var obj : yaml.getList(yaml_path())) {
			if (!(obj instanceof Number)) {
				throw new YamlLoadException("Invalid type for yaml path '" + yaml_path() + "', expected double");
			}

			var val = yaml.getDouble(yaml_path());
			if (annotation.min() != Double.NaN && val < annotation.min()) {
				throw new YamlLoadException("Configuration '" + yaml_path() + "' has an invalid value: Value must be >= " + annotation.min());
			}
			if (annotation.max() != Double.NaN && val > annotation.max()) {
				throw new YamlLoadException("Configuration '" + yaml_path() + "' has an invalid value: Value must be <= " + annotation.max());
			}
		}
	}

	public void load(YamlConfiguration yaml) {
		final var list = new ArrayList<Double>();
		for (var obj : yaml.getList(yaml_path())) {
			list.add(((Number)obj).doubleValue());
		}

		try {
			field.set(owner, list);
		} catch (IllegalAccessException e) {
			throw new RuntimeException("Invalid field access on '" + field.getName() + "'. This is a bug.");
		}
	}
}
