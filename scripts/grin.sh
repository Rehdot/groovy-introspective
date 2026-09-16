#!/bin/sh
java --enable-native-access=ALL-UNNAMED -cp "$HOME/.grin/bin/core-1.0.0.jar" me.redot.grin.core.GrinCli "$@"