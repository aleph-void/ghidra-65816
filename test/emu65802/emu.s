; P-code emulation cases for the 65802 variant (65802:LE:16:default): the 65816 instruction set
; on a 16-bit address bus, so every bank byte is dropped on the bus. emu_cases.txt gives the
; hand-computed results; on a 65816 each of these would touch another bank.
        .p816
        .smart -
        .segment "CODE"
        .a8
        .i8
t_ldalong:  lda f:$123456       ; bus address $3456
            stp
t_ldadbr:   lda $2000           ; DBR=$7e: bus address $2000
            stp
t_ldaabsx:  lda $fffe,x         ; X=4: $fffe+4 wraps to $0002 (65816: next bank)
            stp
t_stalong:  sta f:$7e1000       ; bus address $1000
            stp
t_ldadil:   lda [$30]           ; long pointer $7f1234 -> bus address $1234
            stp
            .a16
            .i16
t_mvn:      mvn #$12,#$34       ; banks $12 -> $34: bus addresses $1000 -> $2000
            stp
            .a16
            .i8
t_jml:      jml $560000 + jml_tgt   ; lands on jml_tgt in bank 0
            stp
jml_tgt:    lda #$4242          ; (16-bit: the emulator decodes a branch target with the
            stp                 ; stored, not the flowed, context: M=0 by default)
t_jsl:      jsl $560000 + sub_jsl   ; JSL/RTL through a bank-$56 address
            stp
sub_jsl:    lda #$1111
            rtl
