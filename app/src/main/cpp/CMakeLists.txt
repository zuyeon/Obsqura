cmake_minimum_required(VERSION 3.10.2)
project(kyberjni)

# C 표준
set(CMAKE_C_STANDARD 99)
set(CMAKE_C_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# 현재 CMake 파일 기준 kyber 디렉토리
set(KYBER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/kyber)

# kyber/*.c 모두 포함
file(GLOB KYBER_SRC "${KYBER_DIR}/*.c")

# 테스트/샘플 소스는 제외 (있다면)
list(FILTER KYBER_SRC EXCLUDE REGEX "kyber_test\\.c$")
list(FILTER KYBER_SRC EXCLUDE REGEX "test.*\\.c$")
list(FILTER KYBER_SRC EXCLUDE REGEX "main\\.c$")
list(FILTER KYBER_SRC EXCLUDE REGEX "x64\\.Debug/.*\\.c$")

# JNI 구현 파일 명시 (cpp/kyber/kyber_jni.c)
set(JNI_SRC "${CMAKE_CURRENT_SOURCE_DIR}/kyber/kyber_jni.c")

# 라이브러리 생성
add_library(kyberjni SHARED ${KYBER_SRC} ${JNI_SRC})

# 로그 라이브러리
find_library(log-lib log)

# 헤더 경로
target_include_directories(kyberjni PRIVATE ${KYBER_DIR})

# 링크
target_link_libraries(kyberjni ${log-lib})
