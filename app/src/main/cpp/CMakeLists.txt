cmake_minimum_required(VERSION 3.10.2)

project("kyberjni")

# 🔹 C 표준 지정
set(CMAKE_C_STANDARD 99)
set(CMAKE_C_EXTENSIONS OFF)

# 🔹 Kyber 소스 디렉토리 정의
set(KYBER_DIR ${CMAKE_SOURCE_DIR}/kyber)

# 🔹 Kyber 관련 C 파일 목록
file(GLOB KYBER_SRC
        ${KYBER_DIR}/*.c
)

# 🔹 JNI C 파일 명시 (직접 지정)
set(JNI_SRC
        ${KYBER_DIR}/kyber_jni.c
)

# 🔹 전체 파일 지정
set(ALL_SRC
        ${KYBER_SRC}
        ${JNI_SRC}
)

# 🔹 C 소스로 강제 지정
set_source_files_properties(${ALL_SRC} PROPERTIES LANGUAGE C)

# 🔹 라이브러리 생성
add_library(
        kyberjni
        SHARED
        ${ALL_SRC}
)

# 🔹 log 라이브러리 찾기
find_library(
        log-lib
        log
)

# 🔹 헤더 디렉토리 등록
target_include_directories(
        kyberjni
        PRIVATE
        ${KYBER_DIR}
)

# 🔹 log 링크
target_link_libraries(
        kyberjni
        ${log-lib}
)
