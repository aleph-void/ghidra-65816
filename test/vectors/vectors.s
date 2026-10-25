; Vector entry-point test: a raw 32 KB image at $8000 with the vectors at $FFE4-$FFFF and no
; other hint where code is. The "65816 Vector Entry Points" analyzer must disassemble each vector
; target in the CPU's mode there; each "; expect" gives the operand width test/vectors-test.sh
; checks (emulation vectors E=1 M=X=1, native vectors M=X=1).
        .p816
        .smart -
        .segment "CODE"
reset:  .a8
        .i8
        lda #$11                ; expect lda8
        ldx #$22                ; expect ldx8
        clc
        xce
        rep #$30
        .a16
        .i16
        lda #$1111              ; expect lda16
loop:   wai
        bra loop
        .a8
        .i8
nmi:    rep #$20
        .a16
        lda #$3333              ; expect lda16
        ldx #$44                ; expect ldx8
        rti
        .a8
irq:    ldy #$55                ; expect ldy8
        rti
copv:   lda #$66                ; expect lda8
        rti
emunmi: lda #$77                ; expect lda8
        rti
        .segment "VECTORS"
        .word copv, $ffff, $ffff, nmi, $ffff, irq   ; $FFE4 COP BRK ABORT NMI (reserved) IRQ
        .word $ffff, $ffff, $ffff, $ffff            ; $FFF0-$FFF7 (COP emulation unused)
        .word $ffff, emunmi, reset, $ffff           ; $FFF8 ABORT, NMI, RESET, IRQ/BRK
