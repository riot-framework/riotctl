#!/bin/bash
export GPG_TTY=$(tty)
mvn release:prepare 
echo "review results in Sonatype's staging repository, then execute 'mvn release:perform'"
