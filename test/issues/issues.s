; Regression program for the upstream issues (achan1989/ghidra-65816 #1-#4, #8, #10, #12)
; and for width changes made by a callee (set8):
; each byte sequence from an issue report, reached from the reset entry point the way a program
; would set its widths. Each "expect:" comment gives the Ghidra listing text the instruction must
; have; test/issues-test.sh also checks that no immediate operand makes a memory reference
; (#10) and that the decompiler sees immediates as constants.
        .p816
        .smart -
        .segment "CODE"
reset:  .a8
        .i8
        sei                     ; expect: SEI
        clc                     ; expect: CLC
        xce                     ; expect: XCE
        ; #4: a2 03 9e 40 21 with X=1 is LDX #$03; STZ $2140,X (not LDX #$9e03; RTI)
        ldx #$03                ; expect: LDX #$0x3
        stz $2140,x             ; expect: STZ !$0x2140,X
        ; #2: a9 80 8d 00 21 with M=1 is LDA #$80; STA $2100
        lda #$80                ; expect: LDA #$0x80
        sta $2100               ; expect: STA !$0x2100
        ; #3: c9 c1 f0 xx ce 30 0f with M=1 is CMP #$c1; BEQ; DEC $0f30
        cmp #$c1                ; expect: CMP #$0xc1
        beq skip                ; expect: BEQ $0x0080??
        dec $0f30               ; expect: DEC !$0xf30
skip:
        ; #8: Secret of Evermore $1088c9: 89 20 d0 xx with M=1 is BIT #$20; BNE (not BIT #$d020)
        bit #$20                ; expect: BIT #$0x20
        bne skip2               ; expect: BNE $0x0080??
        phb                     ; expect: PHB
        plb                     ; expect: PLB
skip2:
        ; #1: 20 e8 80 is JSR $80e8 (resolves, and the callee is followed)
        jsr sub                 ; expect: JSR !$0x00????
        rep #$30                ; expect: REP #$0x30
        .a16
        .i16
        ; #10: immediate loads are values, not references to the instruction bytes
        ldx #$9bcd              ; expect: LDX #$0x9bcd
        ldy #$1fff              ; expect: LDY #$0x1fff
        cpy #$3de5              ; expect: CPY #$0x3de5
        and #$7fff              ; expect: AND #$0x7fff
        bit #$4000              ; expect: BIT #$0x4000
        ; #12: a9 78 22 40 with M=0 is LDA #$2278; RTI (not LDA #$78; JSL)
        lda #$2278              ; expect: LDA #$0x2278
        ; A callee's REP/SEP reaches the caller: set8 returns with M=X=1
        jsr set8                ; expect: JSR !$0x00????
        .a8
        .i8
        lda #$21                ; expect: LDA #$0x21
        ldx #$22                ; expect: LDX #$0x22
        jsr keep                ; expect: JSR !$0x00????
        lda #$23                ; expect: LDA #$0x23
        rti                     ; expect: RTI

set8:   sep #$30                ; expect: SEP #$0x30
        rts                     ; expect: RTS
; keep restores its caller's widths: the caller's context must not change after it
keep:   php                     ; expect: PHP
        rep #$30                ; expect: REP #$0x30
        .a16
        .i16
        lda #$2424              ; expect: LDA #$0x2424
        plp                     ; expect: PLP
        rts                     ; expect: RTS
        .a16
        .i16

; Decompiled by the test: the constants must appear as values ($1234 + $0101 = $1335 is stored).
sub:    .a8
        .i8
        rep #$20                ; expect: REP #$0x20
        .a16
        lda #$1234              ; expect: LDA #$0x1234
        clc                     ; expect: CLC
        adc #$0101              ; expect: ADC #$0x101
        sta $0300               ; expect: STA !$0x300
        sep #$20                ; expect: SEP #$0x20
        .a8
        rts                     ; expect: RTS
