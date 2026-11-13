@ Guest stubs for the host thunks of engine file linux-user/arm/aw-thunk.c: same order as the enum in aw-thunk.h.
@ Arm state, arguments stay where the AAPCS put them (r0-r3, stack); the SVC is taken by the translator.
	.syntax unified
	.arm
	.text
.macro STUB name
	.global \name
	.type \name, %function
\name:
	mov	ip, #(\@ + 1)
	svc	0x9f0100
	bx	lr
.endm
	STUB aw_egl_init
	STUB aw_egl_swap
	STUB glViewport
	STUB glClearColor
	STUB glClear
	STUB glEnable
	STUB glCreateShader
	STUB glShaderSource
	STUB glCompileShader
	STUB glGetShaderiv
	STUB glGetShaderInfoLog
	STUB glCreateProgram
	STUB glAttachShader
	STUB glBindAttribLocation
	STUB glLinkProgram
	STUB glGetProgramiv
	STUB glUseProgram
	STUB glGetUniformLocation
	STUB glEnableVertexAttribArray
	STUB glVertexAttribPointer
	STUB glUniformMatrix4fv
	STUB glUniform1f
	STUB glDrawArrays
	STUB glFinish
	STUB glGetError
	STUB glReadPixels
