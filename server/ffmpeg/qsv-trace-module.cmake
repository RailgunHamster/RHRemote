# Compile only pinned qsvenc.o and preserve both prior overlays and all other bytes.
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS
    "${RHREMOTE_FFMPEG_MODULE_DIR}/prepare-qsv-trace.py" "${RHREMOTE_FFMPEG_MODULE_DIR}/QSV-TRACE-UPSTREAM.json"
    "${RHREMOTE_FFMPEG_MODULE_DIR}/qsv-trace.h" "${RHREMOTE_FFMPEG_MODULE_DIR}/qsv-trace-impl.h")
set(RHREMOTE_QSV_TRACE_GENERATED "${CMAKE_BINARY_DIR}/rhremote-ffmpeg-qsv-trace")
execute_process(COMMAND "${Python3_EXECUTABLE}" "${RHREMOTE_FFMPEG_MODULE_DIR}/prepare-qsv-trace.py" prepare
    --source "${rhremote_ffmpeg_mf_source_SOURCE_DIR}" --prepared "${FFMPEG_PREPARED_BINARIES}" --output "${RHREMOTE_QSV_TRACE_GENERATED}"
    COMMAND_ERROR_IS_FATAL ANY)
add_library(rhremote_ffmpeg_qsv_trace_object OBJECT "${RHREMOTE_QSV_TRACE_GENERATED}/qsvenc.c")
target_compile_features(rhremote_ffmpeg_qsv_trace_object PRIVATE c_std_11)
target_compile_definitions(rhremote_ffmpeg_qsv_trace_object PRIVATE HAVE_AV_CONFIG_H)
target_compile_options(rhremote_ffmpeg_qsv_trace_object PRIVATE -g)
target_include_directories(rhremote_ffmpeg_qsv_trace_object PRIVATE "${RHREMOTE_QSV_TRACE_GENERATED}"
    "${rhremote_ffmpeg_mf_source_SOURCE_DIR}" "${rhremote_ffmpeg_mf_source_SOURCE_DIR}/libavcodec"
    "${FFMPEG_PREPARED_BINARIES}/include" "${RHREMOTE_QSV_VPL_INCLUDE}")
set(RHREMOTE_QSV_TRACE_ARCHIVE "${RHREMOTE_QSV_TRACE_GENERATED}/libavcodec.a")
add_custom_command(OUTPUT "${RHREMOTE_QSV_TRACE_ARCHIVE}"
    BYPRODUCTS "${RHREMOTE_QSV_TRACE_GENERATED}/qsvenc.o" "${RHREMOTE_QSV_TRACE_GENERATED}/libavcodec.provenance.json"
        "${RHREMOTE_QSV_TRACE_GENERATED}/libavcodec.a.abi.json"
    COMMAND "${CMAKE_COMMAND}" -E copy "$<TARGET_OBJECTS:rhremote_ffmpeg_qsv_trace_object>" "${RHREMOTE_QSV_TRACE_GENERATED}/qsvenc.o"
    COMMAND "${Python3_EXECUTABLE}" "${RHREMOTE_FFMPEG_MODULE_DIR}/qsv-abi.py" --kind encode
        --vendor "${FFMPEG_PREPARED_BINARIES}/lib/libavcodec.a" --module "${RHREMOTE_QSV_TRACE_GENERATED}/qsvenc.o"
        --output "${RHREMOTE_QSV_TRACE_GENERATED}/libavcodec.a.abi.json" --objdump "${CMAKE_OBJDUMP}"
    COMMAND "${Python3_EXECUTABLE}" "${RHREMOTE_FFMPEG_MODULE_DIR}/prepare-qsv-trace.py" archive
        --original "${FFMPEG_PREPARED_BINARIES}/lib/libavcodec.a" --base "${RHREMOTE_QSV_GENERATED}/libavcodec.a"
        --base-provenance "${RHREMOTE_QSV_GENERATED}/libavcodec.provenance.json"
        --module "${RHREMOTE_QSV_TRACE_GENERATED}/qsvenc.o" --output "${RHREMOTE_QSV_TRACE_ARCHIVE}"
        --ar "${CMAKE_AR}" --ranlib "${CMAKE_RANLIB}"
    DEPENDS rhremote_ffmpeg_qsv_trace_object $<TARGET_OBJECTS:rhremote_ffmpeg_qsv_trace_object>
        rhremote_ffmpeg_qsv_codec_archive "${RHREMOTE_QSV_GENERATED}/libavcodec.a"
        "${RHREMOTE_QSV_GENERATED}/libavcodec.provenance.json"
        "${RHREMOTE_FFMPEG_MODULE_DIR}/prepare-qsv-trace.py" "${RHREMOTE_FFMPEG_MODULE_DIR}/prepare-mf.py"
        "${RHREMOTE_FFMPEG_MODULE_DIR}/qsv-abi.py" "${RHREMOTE_FFMPEG_MODULE_DIR}/QSV-TRACE-UPSTREAM.json"
    COMMAND_EXPAND_LISTS VERBATIM)
add_custom_target(rhremote_ffmpeg_qsv_trace_archive DEPENDS "${RHREMOTE_QSV_TRACE_ARCHIVE}")
add_library(rhremote_ffmpeg_qsv_trace STATIC IMPORTED GLOBAL)
set_target_properties(rhremote_ffmpeg_qsv_trace PROPERTIES IMPORTED_LOCATION "${RHREMOTE_QSV_TRACE_ARCHIVE}")
target_compile_definitions(rhremote_ffmpeg_qsv_trace INTERFACE RHREMOTE_FFMPEG_MF_TRACE=1 RHREMOTE_FFMPEG_QSV_TRACE=1)
target_link_libraries(rhremote_ffmpeg_qsv_trace INTERFACE dxgi dxguid)
add_dependencies(rhremote_ffmpeg_qsv_trace rhremote_ffmpeg_qsv_trace_archive)
list(FIND FFMPEG_LIBRARIES rhremote_ffmpeg_qsv_codec _rh_qsv_trace_index)
if(_rh_qsv_trace_index LESS 0)
    message(FATAL_ERROR "QSV trace link contract changed: existing QSV/MF overlay is absent")
endif()
list(REMOVE_AT FFMPEG_LIBRARIES ${_rh_qsv_trace_index})
list(INSERT FFMPEG_LIBRARIES ${_rh_qsv_trace_index} rhremote_ffmpeg_qsv_trace)
if(BUILD_TESTS)
    add_executable(test_rhremote_qsv_trace "${RHREMOTE_FFMPEG_MODULE_DIR}/test-qsv-trace.c")
    target_compile_features(test_rhremote_qsv_trace PRIVATE c_std_11)
    target_compile_definitions(test_rhremote_qsv_trace PRIVATE HAVE_AV_CONFIG_H)
    target_include_directories(test_rhremote_qsv_trace PRIVATE "${RHREMOTE_QSV_TRACE_GENERATED}"
        "${rhremote_ffmpeg_mf_source_SOURCE_DIR}" "${rhremote_ffmpeg_mf_source_SOURCE_DIR}/libavcodec"
        "${FFMPEG_PREPARED_BINARIES}/include" "${RHREMOTE_QSV_VPL_INCLUDE}")
    set_target_properties(test_rhremote_qsv_trace PROPERTIES LINKER_LANGUAGE CXX)
    target_link_libraries(test_rhremote_qsv_trace PRIVATE ${FFMPEG_LIBRARIES} bcrypt)
endif()
install(FILES "${RHREMOTE_FFMPEG_MODULE_DIR}/QSV-TRACE-UPSTREAM.json" "${RHREMOTE_FFMPEG_MODULE_DIR}/QSV-TRACE-README.md"
    "${RHREMOTE_QSV_TRACE_GENERATED}/module-source.json" "${RHREMOTE_QSV_TRACE_GENERATED}/libavcodec.provenance.json"
    "${RHREMOTE_QSV_TRACE_GENERATED}/libavcodec.a.abi.json" DESTINATION "assets/ffmpeg-qsv-trace")
