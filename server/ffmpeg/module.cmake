# Recompile the pinned MF source using the original package's generated ABI config.
# All other libavcodec members are verified byte-identical after re-archiving.
if(NOT WIN32 OR NOT CMAKE_SYSTEM_PROCESSOR MATCHES "^(AMD64|amd64|x86_64)$")
    return()
endif()
if(NOT CMAKE_C_COMPILER_ID STREQUAL "GNU")
    message(FATAL_ERROR "RHRemote's pinned MF correction requires the MSYS2 UCRT64 GNU toolchain")
endif()
include(FetchContent)
find_package(Python3 REQUIRED COMPONENTS Interpreter)
set(RHREMOTE_FFMPEG_MODULE_DIR "${CMAKE_CURRENT_LIST_DIR}")
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS
    "${RHREMOTE_FFMPEG_MODULE_DIR}/UPSTREAM.json" "${RHREMOTE_FFMPEG_MODULE_DIR}/prepare-mf.py"
    "${RHREMOTE_FFMPEG_MODULE_DIR}/mf-trace.h" "${RHREMOTE_FFMPEG_MODULE_DIR}/mf-trace-impl.h")
FetchContent_Declare(rhremote_ffmpeg_mf_source
    URL "https://codeload.github.com/FFmpeg/FFmpeg/tar.gz/38b88335f99e76ed89ff3c93f877fdefce736c13"
    URL_HASH SHA256=2ae7e42343cfffb811d15cfe98b6d005f082595fcdf034d30a4ff90cfed9f9c6
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE)
FetchContent_MakeAvailable(rhremote_ffmpeg_mf_source)
set(RHREMOTE_MF_GENERATED "${CMAKE_BINARY_DIR}/rhremote-ffmpeg-mf")
execute_process(COMMAND "${Python3_EXECUTABLE}" "${RHREMOTE_FFMPEG_MODULE_DIR}/prepare-mf.py" prepare
    --source "${rhremote_ffmpeg_mf_source_SOURCE_DIR}" --prepared "${FFMPEG_PREPARED_BINARIES}" --output "${RHREMOTE_MF_GENERATED}"
    RESULT_VARIABLE _rh_mf_prepared COMMAND_ERROR_IS_FATAL ANY)
add_library(rhremote_ffmpeg_mf_object OBJECT "${RHREMOTE_MF_GENERATED}/mfenc.c")
target_compile_features(rhremote_ffmpeg_mf_object PRIVATE c_std_11)
target_compile_definitions(rhremote_ffmpeg_mf_object PRIVATE HAVE_AV_CONFIG_H)
target_include_directories(rhremote_ffmpeg_mf_object PRIVATE
    "${rhremote_ffmpeg_mf_source_SOURCE_DIR}" "${FFMPEG_PREPARED_BINARIES}/include" "${rhremote_ffmpeg_mf_source_SOURCE_DIR}/libavcodec")
set(RHREMOTE_MF_ARCHIVE "${RHREMOTE_MF_GENERATED}/libavcodec.a")
add_custom_command(OUTPUT "${RHREMOTE_MF_ARCHIVE}"
    COMMAND "${CMAKE_COMMAND}" -E copy "$<TARGET_OBJECTS:rhremote_ffmpeg_mf_object>" "${RHREMOTE_MF_GENERATED}/mfenc.o"
    COMMAND "${Python3_EXECUTABLE}" "${RHREMOTE_FFMPEG_MODULE_DIR}/prepare-mf.py" archive
        --original "${FFMPEG_PREPARED_BINARIES}/lib/libavcodec.a" --module "${RHREMOTE_MF_GENERATED}/mfenc.o"
        --output "${RHREMOTE_MF_ARCHIVE}" --ar "${CMAKE_AR}" --ranlib "${CMAKE_RANLIB}"
    DEPENDS rhremote_ffmpeg_mf_object "${RHREMOTE_FFMPEG_MODULE_DIR}/prepare-mf.py" "${FFMPEG_PREPARED_BINARIES}/lib/libavcodec.a"
    COMMAND_EXPAND_LISTS VERBATIM)
add_custom_target(rhremote_ffmpeg_mf_archive DEPENDS "${RHREMOTE_MF_ARCHIVE}")
add_library(rhremote_ffmpeg_mf STATIC IMPORTED GLOBAL)
set_target_properties(rhremote_ffmpeg_mf PROPERTIES IMPORTED_LOCATION "${RHREMOTE_MF_ARCHIVE}")
target_compile_definitions(rhremote_ffmpeg_mf INTERFACE RHREMOTE_FFMPEG_MF_TRACE=1)
add_dependencies(rhremote_ffmpeg_mf rhremote_ffmpeg_mf_archive)
list(FIND FFMPEG_LIBRARIES "${FFMPEG_PREPARED_BINARIES}/lib/libavcodec.a" _rh_mf_library_index)
if(_rh_mf_library_index LESS 0)
    message(FATAL_ERROR "FFmpeg link contract changed: original libavcodec is absent")
endif()
list(REMOVE_AT FFMPEG_LIBRARIES ${_rh_mf_library_index})
list(INSERT FFMPEG_LIBRARIES ${_rh_mf_library_index} rhremote_ffmpeg_mf)
if(BUILD_TESTS)
    # The same CodecAPI boundary test must accept the fixed source and reject the original.
    function(rhremote_add_mf_variant_test name)
        add_executable(${name} "${RHREMOTE_FFMPEG_MODULE_DIR}/test-mf-variant.c")
        target_compile_features(${name} PRIVATE c_std_11)
        target_compile_definitions(${name} PRIVATE HAVE_AV_CONFIG_H)
        target_include_directories(${name} PRIVATE ${ARGN}
            "${rhremote_ffmpeg_mf_source_SOURCE_DIR}" "${FFMPEG_PREPARED_BINARIES}/include" "${rhremote_ffmpeg_mf_source_SOURCE_DIR}/libavcodec")
        set_target_properties(${name} PROPERTIES LINKER_LANGUAGE CXX)
        target_link_libraries(${name} PRIVATE ${FFMPEG_LIBRARIES} bcrypt)
    endfunction()
    rhremote_add_mf_variant_test(test_rhremote_mf_variant "${RHREMOTE_MF_GENERATED}")
    rhremote_add_mf_variant_test(test_rhremote_mf_original_variant)
    add_executable(test_rhremote_mf_trace "${RHREMOTE_FFMPEG_MODULE_DIR}/test-mf-trace.c")
    target_compile_features(test_rhremote_mf_trace PRIVATE c_std_11)
    target_compile_definitions(test_rhremote_mf_trace PRIVATE HAVE_AV_CONFIG_H)
    target_include_directories(test_rhremote_mf_trace PRIVATE "${RHREMOTE_MF_GENERATED}"
        "${rhremote_ffmpeg_mf_source_SOURCE_DIR}" "${FFMPEG_PREPARED_BINARIES}/include" "${rhremote_ffmpeg_mf_source_SOURCE_DIR}/libavcodec")
    set_target_properties(test_rhremote_mf_trace PROPERTIES LINKER_LANGUAGE CXX)
    target_link_libraries(test_rhremote_mf_trace PRIVATE ${FFMPEG_LIBRARIES} bcrypt)
endif()
install(FILES "${RHREMOTE_FFMPEG_MODULE_DIR}/UPSTREAM.json" "${RHREMOTE_FFMPEG_MODULE_DIR}/README.md"
    "${RHREMOTE_MF_GENERATED}/module-source.json" "${RHREMOTE_MF_GENERATED}/libavcodec.provenance.json"
    DESTINATION "assets/ffmpeg-mf")
