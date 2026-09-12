package com.devmanchego.contextextractor.common;

/**
 * A single form-control validator, framework-agnostic.
 *
 * <p>{@code name} is the canonical, argument-free validator key (e.g. {@code "required"},
 * {@code "minlength"}). For Angular this matches the keys exposed at runtime on
 * {@code control.errors} (lower-cased, e.g. {@code Validators.minLength} → {@code "minlength"}),
 * which is what templates test against ({@code errors?.minlength}) — so this is the field to
 * index by when correlating a validator with its error message.
 *
 * <p>{@code args} holds the formatted arguments when present (e.g. {@code "3"} for
 * {@code minLength(3)}), or {@code null} for argument-less validators.
 *
 * <p>{@code rawText} is the pre-formatted {@code "name(args)"} display string the renderers
 * already show today (e.g. {@code "minLength(3)"}) — kept so the display format is decided
 * once, at extraction time, rather than re-derived at render time.
 */
public record ValidatorInfo(String name, String args, String rawText) {

    public static ValidatorInfo simple(String name) {
        return new ValidatorInfo(name, null, name);
    }

    public static ValidatorInfo withArgs(String name, String args) {
        String raw = (args == null || args.isEmpty()) ? name : name + "(" + args + ")";
        return new ValidatorInfo(name, args, raw);
    }
}
