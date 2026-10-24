"""A fresh Ninja graph must know how to produce the actual predecessor provenance."""
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

HERE=Path(__file__).resolve().parent
def tool(name):
    found=shutil.which(name)
    if not found:
        candidate=Path('C:/msys64/ucrt64/bin')/(name+'.exe')
        if candidate.is_file():found=str(candidate)
    return found

class QsvCleanBuildGraph(unittest.TestCase):
    def test_real_modules_declare_provenance_without_cached_files_and_reject_old_suffix(self):
        paths={name:tool(name) for name in ('cmake','ninja','gcc','g++')}
        if not all(paths.values()):self.skipTest('CMake/Ninja/GNU C and C++ tools required for build-graph regression')
        actual=(HERE/'qsv-module.cmake').read_text(encoding='utf-8')
        self.assertEqual(1,actual.count('${RHREMOTE_QSV_GENERATED}/${_rh_qsv_provenance}'))
        broken=actual.replace('${RHREMOTE_QSV_GENERATED}/${_rh_qsv_provenance}',
                              '${RHREMOTE_QSV_GENERATED}/${_rh_qsv_library}.provenance.json')
        with tempfile.TemporaryDirectory(prefix='rhremote-qsv-graph-') as directory:
            root=Path(directory)
            for name,text,successful in (('original-suffix',broken,False),('correct-suffix',actual,True)):
                source=root/name;source.mkdir();build=source/'cmake-build-graph'
                module=source/'qsv-module.cmake';module.write_text(text,encoding='utf-8')
                cmake=r'''
cmake_minimum_required(VERSION 3.22)
project(RhQsvDependencyGraph C CXX)
set(BUILD_TESTS OFF)
set(RHREMOTE_FFMPEG_MODULE_DIR "@MODULE_DIRECTORY@")
set(Python3_EXECUTABLE "@PYTHON_EXECUTABLE@")
set(FFMPEG_PREPARED_BINARIES "${CMAKE_CURRENT_BINARY_DIR}/vendor")
set(rhremote_ffmpeg_mf_source_SOURCE_DIR "${CMAKE_CURRENT_BINARY_DIR}/unused-source")
set(RHREMOTE_QSV_VPL_INCLUDE "${CMAKE_CURRENT_BINARY_DIR}/sdk")
file(MAKE_DIRECTORY "${FFMPEG_PREPARED_BINARIES}/lib" "${RHREMOTE_QSV_VPL_INCLUDE}")
file(WRITE "${FFMPEG_PREPARED_BINARIES}/lib/libavutil.a" "fixture")
file(WRITE "${FFMPEG_PREPARED_BINARIES}/lib/libavcodec.a" "fixture")
file(WRITE "${RHREMOTE_QSV_VPL_INCLUDE}/mfxvideo.h" "")
set(RHREMOTE_MF_GENERATED "${CMAKE_CURRENT_BINARY_DIR}/rhremote-ffmpeg-mf")
file(MAKE_DIRECTORY "${RHREMOTE_MF_GENERATED}")
set(RHREMOTE_MF_ARCHIVE "${RHREMOTE_MF_GENERATED}/libavcodec.a")
add_custom_command(OUTPUT "${RHREMOTE_MF_ARCHIVE}"
  COMMAND "${CMAKE_COMMAND}" -E touch "${RHREMOTE_MF_ARCHIVE}")
add_custom_target(rhremote_ffmpeg_mf_archive DEPENDS "${RHREMOTE_MF_ARCHIVE}")
add_library(rhremote_ffmpeg_mf STATIC IMPORTED GLOBAL)
set_target_properties(rhremote_ffmpeg_mf PROPERTIES IMPORTED_LOCATION "${RHREMOTE_MF_ARCHIVE}")
add_dependencies(rhremote_ffmpeg_mf rhremote_ffmpeg_mf_archive)
set(FFMPEG_LIBRARIES rhremote_ffmpeg_mf "${FFMPEG_PREPARED_BINARIES}/lib/libavutil.a")
# Source/archive byte guards are exercised separately. This fixture tests only the
# real CMake rules with no archive/provenance outputs present; no SDK is executed.
function(execute_process)
  if(NOT "${ARGV0}" STREQUAL "COMMAND" OR NOT "${ARGV2}" MATCHES "/prepare-qsv(-trace)?\\.py$" OR NOT "${ARGV3}" STREQUAL "prepare")
    message(FATAL_ERROR "Unexpected source-stage command in build-graph fixture")
  endif()
endfunction()
file(MAKE_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}/rhremote-ffmpeg-qsv" "${CMAKE_CURRENT_BINARY_DIR}/rhremote-ffmpeg-qsv-trace")
file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/rhremote-ffmpeg-qsv/qsv.c" "int fixture_qsv;\n")
file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/rhremote-ffmpeg-qsv/hwcontext_qsv.c" "int fixture_util;\n")
file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/rhremote-ffmpeg-qsv-trace/qsvenc.c" "int fixture_encode;\n")
include("@BASE_MODULE_PATH@")
include("${RHREMOTE_FFMPEG_MODULE_DIR}/qsv-trace-module.cmake")
'''.replace('@MODULE_DIRECTORY@',HERE.as_posix()).replace('@PYTHON_EXECUTABLE@',Path(sys.executable).as_posix()).replace('@BASE_MODULE_PATH@',module.as_posix())
                (source/'CMakeLists.txt').write_text(cmake,encoding='utf-8')
                configured=subprocess.run([paths['cmake'],'-S',str(source),'-B',str(build),'-G','Ninja',
                    '-DCMAKE_MAKE_PROGRAM='+paths['ninja'],'-DCMAKE_C_COMPILER='+paths['gcc'],
                    '-DCMAKE_CXX_COMPILER='+paths['g++']],capture_output=True,text=True,timeout=60)
                self.assertEqual(0,configured.returncode,configured.stdout+configured.stderr)
                proof=build/'rhremote-ffmpeg-qsv/libavcodec.provenance.json'
                self.assertFalse(proof.exists(),'Fixture must not manufacture the missing metadata')
                planned=subprocess.run([paths['ninja'],'-C',str(build),'-n','rhremote_ffmpeg_qsv_trace_archive'],
                    capture_output=True,text=True,timeout=30)
                with self.subTest(suffix=name):
                    if successful:self.assertEqual(0,planned.returncode,planned.stdout+planned.stderr)
                    else:
                        self.assertNotEqual(0,planned.returncode)
                        self.assertIn('libavcodec.provenance.json',planned.stdout+planned.stderr)
                        self.assertIn('no known rule',planned.stdout+planned.stderr)
                self.assertFalse(proof.exists(),'Dry-run cannot hide the dependency error by creating outputs')

if __name__=='__main__':unittest.main()
