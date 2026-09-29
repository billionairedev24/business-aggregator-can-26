package ca.northline.shared;

import com.github.f4b6a3.ulid.UlidCreator;

public final class Ids { private Ids() {} public static String next() { return UlidCreator.getMonotonicUlid().toString(); } }
