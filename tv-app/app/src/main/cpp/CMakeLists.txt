cmake_minimum_required(VERSION 3.10)
project(pongpunch C)

# Tiny helper that frees disk space inside a file (fallocate hole punching); see HolePunch.java.
add_library(pongpunch SHARED punch.c)
