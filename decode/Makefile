# Sample makefile

!include <win32.mak>

all: decode.exe

.c.obj:
  $(cc) $(cdebug) $(cflags) /MD $(cvars) $*.c

.cpp.obj:
  $(cc) $(cdebug) $(cflags) /MD $(cvars) $*.cpp

decode.exe: decode.obj bigdigits.obj rmd160.obj sha2.obj
  $(link) /OPT:ref $(ldebug) $(conflags) -out:decode.exe decode.obj rmd160.obj bigdigits.obj sha2.obj $(conlibs)