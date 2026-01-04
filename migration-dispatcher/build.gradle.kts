plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    java
}

group = "com.example"
version = "0.0.1-SNAPSHOT"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-amqp")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("commons-cli:commons-cli:1.8.0")
    implementation("org.springframework.boot:spring-boot-starter")
    
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
