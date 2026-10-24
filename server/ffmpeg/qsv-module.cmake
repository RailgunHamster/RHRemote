# Overlay only the two pinned QSV objects; retain the already verified MF object.
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS
    "${RHREMOTE_FFMPEG_MODULE_DIR}/prepare-qsv.py" "${RHREMOTE_FFMPEG_MODULE_DIR}/QSV-UPSTREAM.json"
    "${RHREMOTE_FFMPEG_MODULE_DIR}/qsv-fast.h" "${RHREMOTE_FFMPEG_MODULE_DIR}/qsv-abi.py")
set(RHREMOTE_QSV_GENERATED "${CMAKE_BINARY_DIR}/rhremote-ffmpeg-qsv")
find_path(RHREMOTE_QSV_VPL_INCLUDE NAMES mfxvideo.h PATH_SUFFIXES vpl REQUIRED)
execute_process(COMMAND "${Python3_EXECUTABLE}" "${RHREMOTE_FFMPEG_MODULE_DIR}/prepare-qsv.py" prepare
    --source "${rhremote_ffmpeg_mf_source_SOURCE_DIR}" --prepared "${FFMPEG_PREPARED_BINARIES}" --output "${RHREMOTE_QSV_GENERATED}"
    COMMAND_ERROR_IS_FATAL ANY)
foreach(_rh_qsv_kind IN ITEMS util codec)
    if(_rh_qsv_kind STREQUAL "util")
        set(_rh_qsv_source hwcontext_qsv)
        set(_rh_qsv_folder libavutil)
        set(_rh_qsv_library libavutil.a)
        set(_rh_qsv_base "${FFMPEG_PREPARED_BINARIES}/lib/libavutil.a")
        set(_rh_qsv_extra)
        set(_rh_qsv_dependencies)
    else()
        set(_rh_qsv_source qsv)
        set(_rh_qsv_folder libavcodec)
        set(_rh_qsv_library libavcodec.a)
        set(_rh_qsv_base "${RHREMOTE_MF_ARCHIVE}")
        set(_rh_qsv_extra --mf-provenance "${RHREMOTE_MF_GENERATED}/libavcodec.provenance.json")
        set(_rh_qsv_dependencies rhremote_ffmpeg_mf_archive)
    endif()
    add_library(rhremote_ffmpeg_qsv_${_rh_qsv_kind}_object OBJECT "${RHREMOTE_QSV_GENERATED}/${_rh_qsv_source}.c")
    target_compile_features(rhremote_ffmpeg_qsv_${_rh_qsv_kind}_object PRIVATE c_std_11)
    target_compile_definitions(rhremote_ffmpeg_qsv_${_rh_qsv_kind}_object PRIVATE HAVE_AV_CONFIG_H)
    target_compile_options(rhremote_ffmpeg_qsv_${_rh_qsv_kind}_object PRIVATE -g)
    target_include_directories(rhremote_ffmpeg_qsv_${_rh_qsv_kind}_object PRIVATE
        "${RHREMOTE_QSV_GENERATED}" "${rhremote_ffmpeg_mf_source_SOURCE_DIR}"
        "${rhremote_ffmpeg_mf_source_SOURCE_DIR}/${_rh_qsv_folder}" "${FFMPEG_PREPARED_BINARIES}/include"
        "${RHREMOTE_QSV_VPL_INCLUDE}")
    set(_rh_qsv_archive "${RHREMOTE_QSV_GENERATED}/${_rh_qsv_library}")
    string(REGEX REPLACE "\\.a$" ".provenance.json" _rh_qsv_provenance "${_rh_qsv_library}")
    add_custom_command(OUTPUT "${_rh_qsv_archive}"
        BYPRODUCTS "${RHREMOTE_QSV_GENERATED}/${_rh_qsv_source}.o" "${RHREMOTE_QSV_GENERATED}/${_rh_qsv_library}.abi.json"
            "${RHREMOTE_QSV_GENERATED}/${_rh_qsv_provenance}"
        COMMAND "${CMAKE_COMMAND}" -E copy "$<TARGET_OBJECTS:rhremote_ffmpeg_qsv_${_rh_qsv_kind}_object>" "${RHREMOTE_QSV_GENERATED}/${_rh_qsv_source}.o"
        COMMAND "${Python3_EXECUTABLE}" "${RHREMOTE_FFMPEG_MODULE_DIR}/qsv-abi.py" --kind "${_rh_qsv_kind}"
            --vendor "${FFMPEG_PREPARED_BINARIES}/lib/${_rh_qsv_library}" --module "${RHREMOTE_QSV_GENERATED}/${_rh_qsv_source}.o"
            --output "${RHREMOTE_QSV_GENERATED}/${_rh_qsv_library}.abi.json" --objdump "${CMAKE_OBJDUMP}"
        COMMAND "${Python3_EXECUTABLE}" "${RHREMOTE_FFMPEG_MODULE_DIR}/prepare-qsv.py" archive --kind "${_rh_qsv_kind}"
            --original "${FFMPEG_PREPARED_BINARIES}/lib/${_rh_qsv_library}" --base "${_rh_qsv_base}"
            --module "${RHREMOTE_QSV_GENERATED}/${_rh_qsv_source}.o" --output "${_rh_qsv_archive}"
            --ar "${CMAKE_AR}" --ranlib "${CMAKE_RANLIB}" ${_rh_qsv_extra}
        DEPENDS rhremote_ffmpeg_qsv_${_rh_qsv_kind}_object $<TARGET_OBJECTS:rhremote_ffmpeg_qsv_${_rh_qsv_kind}_object>
            ${_rh_qsv_dependencies}
            "${_rh_qsv_base}" "${RHREMOTE_FFMPEG_MODULE_DIR}/prepare-qsv.py" "${RHREMOTE_FFMPEG_MODULE_DIR}/prepare-mf.py"
            "${RHREMOTE_FFMPEG_MODULE_DIR}/qsv-abi.py"
        COMMAND_EXPAND_LISTS VERBATIM)
    add_custom_target(rhremote_ffmpeg_qsv_${_rh_qsv_kind}_archive DEPENDS "${_rh_qsv_archive}")
    add_library(rhremote_ffmpeg_qsv_${_rh_qsv_kind} STATIC IMPORTED GLOBAL)
    set_target_properties(rhremote_ffmpeg_qsv_${_rh_qsv_kind} PROPERTIES IMPORTED_LOCATION "${_rh_qsv_archive}")
    target_link_libraries(rhremote_ffmpeg_qsv_${_rh_qsv_kind} INTERFACE dxgi dxguid)
    add_dependencies(rhremote_ffmpeg_qsv_${_rh_qsv_kind} rhremote_ffmpeg_qsv_${_rh_qsv_kind}_archive)
    set(_rh_qsv_replace "${FFMPEG_PREPARED_BINARIES}/lib/${_rh_qsv_library}")
    if(_rh_qsv_kind STREQUAL "codec")
        set(_rh_qsv_replace rhremote_ffmpeg_mf)
        target_compile_definitions(rhremote_ffmpeg_qsv_codec INTERFACE RHREMOTE_FFMPEG_MF_TRACE=1)
    endif()
    list(FIND FFMPEG_LIBRARIES "${_rh_qsv_replace}" _rh_qsv_index)
    if(_rh_qsv_index LESS 0)
        message(FATAL_ERROR "QSV overlay link contract changed: ${_rh_qsv_replace} is absent")
    endif()
    list(REMOVE_AT FFMPEG_LIBRARIES ${_rh_qsv_index})
    list(INSERT FFMPEG_LIBRARIES ${_rh_qsv_index} rhremote_ffmpeg_qsv_${_rh_qsv_kind})
endforeach()
if(BUILD_TESTS)
    function(rhremote_add_qsv_boundary name device original)
        add_executable(${name} "${RHREMOTE_FFMPEG_MODULE_DIR}/test-qsv-fast.c")
        target_compile_features(${name} PRIVATE c_std_11)
        target_compile_definitions(${name} PRIVATE HAVE_AV_CONFIG_H RHREMOTE_QSV_DEVICE=${device})
        if(original)
            target_compile_definitions(${name} PRIVATE RHREMOTE_QSV_TEST_ORIGINAL=1)
        else()
            target_include_directories(${name} PRIVATE "${RHREMOTE_QSV_GENERATED}")
        endif()
        if(device)
            set(_rh_qsv_private libavutil)
        else()
            set(_rh_qsv_private libavcodec)
        endif()
        target_include_directories(${name} PRIVATE "${rhremote_ffmpeg_mf_source_SOURCE_DIR}"
            "${rhremote_ffmpeg_mf_source_SOURCE_DIR}/${_rh_qsv_private}"
            "${FFMPEG_PREPARED_BINARIES}/include" "${RHREMOTE_QSV_VPL_INCLUDE}")
        set_target_properties(${name} PROPERTIES LINKER_LANGUAGE CXX)
        target_link_libraries(${name} PRIVATE ${FFMPEG_LIBRARIES} d3d11 dxgi dxguid bcrypt)
    endfunction()
    rhremote_add_qsv_boundary(test_rhremote_qsv_device 1 FALSE)
    rhremote_add_qsv_boundary(test_rhremote_qsv_child 0 FALSE)
    rhremote_add_qsv_boundary(test_rhremote_qsv_original_device 1 TRUE)
    rhremote_add_qsv_boundary(test_rhremote_qsv_original_child 0 TRUE)
endif()
install(FILES "${RHREMOTE_FFMPEG_MODULE_DIR}/QSV-UPSTREAM.json" "${RHREMOTE_FFMPEG_MODULE_DIR}/QSV-README.md"
    "${RHREMOTE_QSV_GENERATED}/module-source.json" "${RHREMOTE_QSV_GENERATED}/libavutil.provenance.json"
    "${RHREMOTE_QSV_GENERATED}/libavcodec.provenance.json" "${RHREMOTE_QSV_GENERATED}/libavutil.a.abi.json"
    "${RHREMOTE_QSV_GENERATED}/libavcodec.a.abi.json" DESTINATION "assets/ffmpeg-qsv")
