package org.oddlama.vane.annotation.processor;

import java.util.Set;
import java.lang.annotation.Annotation;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;

import org.oddlama.vane.annotation.command.Name;
import org.oddlama.vane.annotation.command.Aliases;
import org.oddlama.vane.annotation.command.Description;

@SupportedAnnotationTypes({
	"org.oddlama.vane.annotation.command.VaneCommand",
	"org.oddlama.vane.annotation.command.Name",
	"org.oddlama.vane.annotation.command.Aliases",
	"org.oddlama.vane.annotation.command.Description",
})
@SupportedSourceVersion(SourceVersion.RELEASE_11)
public class CommandAnnotationProcessor extends AbstractProcessor {
	@SuppressWarnings({"rawtypes", "unchecked"})
	private static final Class<? extends Annotation>[] mandatory_annotations = new Class[] {
	    Name.class,
	    Description.class,
	};

	@Override
	public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round_env) {
		for (var annotation : annotations) {
			round_env.getElementsAnnotatedWith(annotation)
				.forEach(a -> verify_is_class(annotation, a));
			round_env.getElementsAnnotatedWith(annotation)
				.forEach(a -> verify_extends_command(annotation, a));

			// Verify that all mandatory annotations are present
			if (annotation.asType().toString().equals("org.oddlama.vane.annotation.command.VaneCommand")) {
				round_env.getElementsAnnotatedWith(annotation)
					.forEach(this::verify_has_annotations);
			}
		}

		return true;
	}

	private void verify_has_annotations(Element element) {
		// Only check subclasses
		if (element.asType().toString().equals("org.oddlama.vane.core.command.Command")) {
			return;
		}

		for (var a_cls : mandatory_annotations) {
			if (element.getAnnotation(a_cls) == null) {
				processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, element.asType().toString() + ": missing @" + a_cls.getSimpleName() + " annotation");
			}
		}
	}

	private void verify_is_class(TypeElement annotation, Element element) {
		if (element.getKind() != ElementKind.CLASS) {
			processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "@" + annotation.getSimpleName() + " must be applied to a class");
		}
	}

	private void verify_extends_command(TypeElement annotation, Element element) {
		var t = (TypeElement)element;
		if (!t.toString().equals("org.oddlama.vane.core.command.Command") && !t.getSuperclass().toString().equals("org.oddlama.vane.core.command.Command")) {
			processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "@" + annotation.getSimpleName() + " must be applied to a class inheriting from org.oddlama.vane.core.command.Command");
		}
	}
}