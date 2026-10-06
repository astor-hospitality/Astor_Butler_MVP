plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
    // Android ships its own org.json, older and stricter than the library usually found under that name.
    // This artifact is that Android implementation, so the code is compiled and tested against what the
    // phone really has: a method that exists only in the newer library does not compile here.
    compileOnly("com.vaadin.external.google:android-json:0.0.20131108.vaadin1")
    testImplementation("com.vaadin.external.google:android-json:0.0.20131108.vaadin1")
    testImplementation("junit:junit:4.13.2")
}
