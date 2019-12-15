<template>
    <div class="term" ref="console"></div>
</template>

<script>
    import 'xterm/css/xterm.css'
    import { Terminal } from 'xterm'
    import { FitAddon } from 'xterm-addon-fit'
    export default {
        name: "CVTerminal",
        data() {
            return {
                colors: {
                    info: "\u001b[32m",
                    warning: "\u001b[33m",
                    error: "\u001b[31m",
                    reset: "\u001b[0m"
                }
            }
        },
        methods: {
            writeLog(type, text) {
                this.$terminal.writeln(`${this.colors[type]} ${text} ${this.colors['reset']}`);
            }
        },
        watch: {
            cols(c) {
                this.$terminal.resize(c, this.rows);
            },
            rows(r) {
                this.$terminal.resize(this.cols, r);
            }
        },
        mounted() {
            let term = new Terminal();
            let fitAddon = new FitAddon();
            term.loadAddon(fitAddon);
            term.open(this.$refs.console);
            term.onResize(()=> this.$fit());
            this.$terminal = term;
            this.$fit = fitAddon;
        }
    }
</script>

<style>
    .xterm{
        width: inherit;
        height: inherit;
        overflow: hidden;
    }
    .xterm-viewport {
        overflow-y: hidden !important;
    }

</style>