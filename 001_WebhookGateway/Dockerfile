# 빌드 스테이지. 의존성 해석과 소스 컴파일을 나눠 캐시를 살린다.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

COPY gradle gradle
COPY gradlew build.gradle settings.gradle ./
# Windows 에서 만든 gradlew 는 CRLF 일 수 있다. 그대로 두면 exec 이 실패한다.
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew
RUN ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

COPY src src
RUN ./gradlew --no-daemon bootJar -x test

# 실행 스테이지. JDK 대신 JRE 로 이미지를 줄인다.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
