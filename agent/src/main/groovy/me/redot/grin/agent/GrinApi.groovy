package me.redot.grin.agent

import groovy.transform.CompileStatic

@CompileStatic
class GrinApi {

    final GrinHeader header = new GrinHeader()

    @Override
    String toString() {
        return 'groovy introspective runtime API'
    }
}
