@LIMIT = constant i32 120

declare void @printInt(i32) 

declare void @printlnInt(i32) 

declare i32 @getInt() 

define i32 @main() {
entry.0:
  %tmp.0 = alloca [121 x i1]
  %tmp.1 = alloca [121 x i1]
  %tmp.130 = alloca i32
  %tmp.154 = alloca i32
  %tmp.155 = alloca i32
  %tmp.139 = alloca i32
  %tmp.167 = alloca i32
  %tmp.2 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 0
  store i1 1, ptr %tmp.2
  %tmp.3 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 1
  store i1 1, ptr %tmp.3
  %tmp.4 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 2
  store i1 1, ptr %tmp.4
  %tmp.5 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 3
  store i1 1, ptr %tmp.5
  %tmp.6 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 4
  store i1 1, ptr %tmp.6
  %tmp.7 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 5
  store i1 1, ptr %tmp.7
  %tmp.8 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 6
  store i1 1, ptr %tmp.8
  %tmp.9 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 7
  store i1 1, ptr %tmp.9
  %tmp.10 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 8
  store i1 1, ptr %tmp.10
  %tmp.11 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 9
  store i1 1, ptr %tmp.11
  %tmp.12 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 10
  store i1 1, ptr %tmp.12
  %tmp.13 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 11
  store i1 1, ptr %tmp.13
  %tmp.14 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 12
  store i1 1, ptr %tmp.14
  %tmp.15 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 13
  store i1 1, ptr %tmp.15
  %tmp.16 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 14
  store i1 1, ptr %tmp.16
  %tmp.17 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 15
  store i1 1, ptr %tmp.17
  %tmp.18 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 16
  store i1 1, ptr %tmp.18
  %tmp.19 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 17
  store i1 1, ptr %tmp.19
  %tmp.20 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 18
  store i1 1, ptr %tmp.20
  %tmp.21 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 19
  store i1 1, ptr %tmp.21
  %tmp.22 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 20
  store i1 1, ptr %tmp.22
  %tmp.23 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 21
  store i1 1, ptr %tmp.23
  %tmp.24 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 22
  store i1 1, ptr %tmp.24
  %tmp.25 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 23
  store i1 1, ptr %tmp.25
  %tmp.26 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 24
  store i1 1, ptr %tmp.26
  %tmp.27 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 25
  store i1 1, ptr %tmp.27
  %tmp.28 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 26
  store i1 1, ptr %tmp.28
  %tmp.29 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 27
  store i1 1, ptr %tmp.29
  %tmp.30 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 28
  store i1 1, ptr %tmp.30
  %tmp.31 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 29
  store i1 1, ptr %tmp.31
  %tmp.32 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 30
  store i1 1, ptr %tmp.32
  %tmp.33 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 31
  store i1 1, ptr %tmp.33
  %tmp.34 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 32
  store i1 1, ptr %tmp.34
  %tmp.35 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 33
  store i1 1, ptr %tmp.35
  %tmp.36 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 34
  store i1 1, ptr %tmp.36
  %tmp.37 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 35
  store i1 1, ptr %tmp.37
  %tmp.38 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 36
  store i1 1, ptr %tmp.38
  %tmp.39 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 37
  store i1 1, ptr %tmp.39
  %tmp.40 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 38
  store i1 1, ptr %tmp.40
  %tmp.41 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 39
  store i1 1, ptr %tmp.41
  %tmp.42 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 40
  store i1 1, ptr %tmp.42
  %tmp.43 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 41
  store i1 1, ptr %tmp.43
  %tmp.44 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 42
  store i1 1, ptr %tmp.44
  %tmp.45 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 43
  store i1 1, ptr %tmp.45
  %tmp.46 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 44
  store i1 1, ptr %tmp.46
  %tmp.47 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 45
  store i1 1, ptr %tmp.47
  %tmp.48 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 46
  store i1 1, ptr %tmp.48
  %tmp.49 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 47
  store i1 1, ptr %tmp.49
  %tmp.50 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 48
  store i1 1, ptr %tmp.50
  %tmp.51 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 49
  store i1 1, ptr %tmp.51
  %tmp.52 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 50
  store i1 1, ptr %tmp.52
  %tmp.53 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 51
  store i1 1, ptr %tmp.53
  %tmp.54 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 52
  store i1 1, ptr %tmp.54
  %tmp.55 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 53
  store i1 1, ptr %tmp.55
  %tmp.56 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 54
  store i1 1, ptr %tmp.56
  %tmp.57 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 55
  store i1 1, ptr %tmp.57
  %tmp.58 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 56
  store i1 1, ptr %tmp.58
  %tmp.59 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 57
  store i1 1, ptr %tmp.59
  %tmp.60 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 58
  store i1 1, ptr %tmp.60
  %tmp.61 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 59
  store i1 1, ptr %tmp.61
  %tmp.62 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 60
  store i1 1, ptr %tmp.62
  %tmp.63 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 61
  store i1 1, ptr %tmp.63
  %tmp.64 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 62
  store i1 1, ptr %tmp.64
  %tmp.65 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 63
  store i1 1, ptr %tmp.65
  %tmp.66 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 64
  store i1 1, ptr %tmp.66
  %tmp.67 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 65
  store i1 1, ptr %tmp.67
  %tmp.68 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 66
  store i1 1, ptr %tmp.68
  %tmp.69 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 67
  store i1 1, ptr %tmp.69
  %tmp.70 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 68
  store i1 1, ptr %tmp.70
  %tmp.71 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 69
  store i1 1, ptr %tmp.71
  %tmp.72 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 70
  store i1 1, ptr %tmp.72
  %tmp.73 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 71
  store i1 1, ptr %tmp.73
  %tmp.74 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 72
  store i1 1, ptr %tmp.74
  %tmp.75 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 73
  store i1 1, ptr %tmp.75
  %tmp.76 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 74
  store i1 1, ptr %tmp.76
  %tmp.77 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 75
  store i1 1, ptr %tmp.77
  %tmp.78 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 76
  store i1 1, ptr %tmp.78
  %tmp.79 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 77
  store i1 1, ptr %tmp.79
  %tmp.80 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 78
  store i1 1, ptr %tmp.80
  %tmp.81 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 79
  store i1 1, ptr %tmp.81
  %tmp.82 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 80
  store i1 1, ptr %tmp.82
  %tmp.83 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 81
  store i1 1, ptr %tmp.83
  %tmp.84 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 82
  store i1 1, ptr %tmp.84
  %tmp.85 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 83
  store i1 1, ptr %tmp.85
  %tmp.86 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 84
  store i1 1, ptr %tmp.86
  %tmp.87 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 85
  store i1 1, ptr %tmp.87
  %tmp.88 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 86
  store i1 1, ptr %tmp.88
  %tmp.89 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 87
  store i1 1, ptr %tmp.89
  %tmp.90 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 88
  store i1 1, ptr %tmp.90
  %tmp.91 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 89
  store i1 1, ptr %tmp.91
  %tmp.92 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 90
  store i1 1, ptr %tmp.92
  %tmp.93 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 91
  store i1 1, ptr %tmp.93
  %tmp.94 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 92
  store i1 1, ptr %tmp.94
  %tmp.95 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 93
  store i1 1, ptr %tmp.95
  %tmp.96 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 94
  store i1 1, ptr %tmp.96
  %tmp.97 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 95
  store i1 1, ptr %tmp.97
  %tmp.98 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 96
  store i1 1, ptr %tmp.98
  %tmp.99 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 97
  store i1 1, ptr %tmp.99
  %tmp.100 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 98
  store i1 1, ptr %tmp.100
  %tmp.101 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 99
  store i1 1, ptr %tmp.101
  %tmp.102 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 100
  store i1 1, ptr %tmp.102
  %tmp.103 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 101
  store i1 1, ptr %tmp.103
  %tmp.104 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 102
  store i1 1, ptr %tmp.104
  %tmp.105 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 103
  store i1 1, ptr %tmp.105
  %tmp.106 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 104
  store i1 1, ptr %tmp.106
  %tmp.107 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 105
  store i1 1, ptr %tmp.107
  %tmp.108 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 106
  store i1 1, ptr %tmp.108
  %tmp.109 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 107
  store i1 1, ptr %tmp.109
  %tmp.110 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 108
  store i1 1, ptr %tmp.110
  %tmp.111 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 109
  store i1 1, ptr %tmp.111
  %tmp.112 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 110
  store i1 1, ptr %tmp.112
  %tmp.113 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 111
  store i1 1, ptr %tmp.113
  %tmp.114 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 112
  store i1 1, ptr %tmp.114
  %tmp.115 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 113
  store i1 1, ptr %tmp.115
  %tmp.116 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 114
  store i1 1, ptr %tmp.116
  %tmp.117 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 115
  store i1 1, ptr %tmp.117
  %tmp.118 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 116
  store i1 1, ptr %tmp.118
  %tmp.119 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 117
  store i1 1, ptr %tmp.119
  %tmp.120 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 118
  store i1 1, ptr %tmp.120
  %tmp.121 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 119
  store i1 1, ptr %tmp.121
  %tmp.122 = getelementptr [121 x i1], ptr %tmp.1, i32 0, i32 120
  store i1 1, ptr %tmp.122
  %tmp.123 = getelementptr i1, ptr null, i32 121
  %tmp.124 = ptrtoint ptr %tmp.123 to i32
  call void @llvm.memcpy.p0.p0.i32(ptr %tmp.0, ptr %tmp.1, i32 %tmp.124, i1 0)
  %tmp.126 = getelementptr [121 x i1], ptr %tmp.0, i32 0, i32 0
  %tmp.127 = load i1, ptr %tmp.126
  store i1 0, ptr %tmp.126
  %tmp.128 = getelementptr [121 x i1], ptr %tmp.0, i32 0, i32 1
  %tmp.129 = load i1, ptr %tmp.128
  store i1 0, ptr %tmp.128
  store i32 2, ptr %tmp.130
  br label %while_cond.0

return.0:
  ret i32 0

while_cond.0:
  %tmp.131 = load i32, ptr %tmp.130
  %tmp.132 = load i32, ptr %tmp.130
  %tmp.133 = mul i32 %tmp.131, %tmp.132
  %tmp.134 = load i32, ptr @LIMIT
  %tmp.135 = icmp ule i32 %tmp.133, %tmp.134
  br i1 %tmp.135, label %while_body.0, label %while_after.0

while_body.0:
  %tmp.136 = load i32, ptr %tmp.130
  %tmp.137 = getelementptr [121 x i1], ptr %tmp.0, i32 0, i32 %tmp.136
  %tmp.138 = load i1, ptr %tmp.137
  br i1 %tmp.138, label %if_then.0, label %if_else.0

while_after.0:
  store i32 0, ptr %tmp.154
  store i32 2, ptr %tmp.155
  br label %while_cond.2

if_then.0:
  %tmp.140 = load i32, ptr %tmp.130
  %tmp.141 = load i32, ptr %tmp.130
  %tmp.142 = mul i32 %tmp.140, %tmp.141
  store i32 %tmp.142, ptr %tmp.139
  br label %while_cond.1

if_else.0:
  br label %if_merge.0

if_merge.0:
  %tmp.152 = load i32, ptr %tmp.130
  %tmp.153 = add i32 %tmp.152, 1
  store i32 %tmp.153, ptr %tmp.130
  br label %while_cond.0

while_cond.1:
  %tmp.143 = load i32, ptr %tmp.139
  %tmp.144 = load i32, ptr @LIMIT
  %tmp.145 = icmp ule i32 %tmp.143, %tmp.144
  br i1 %tmp.145, label %while_body.1, label %while_after.1

while_body.1:
  %tmp.146 = load i32, ptr %tmp.139
  %tmp.147 = getelementptr [121 x i1], ptr %tmp.0, i32 0, i32 %tmp.146
  %tmp.148 = load i1, ptr %tmp.147
  store i1 0, ptr %tmp.147
  %tmp.149 = load i32, ptr %tmp.139
  %tmp.150 = load i32, ptr %tmp.130
  %tmp.151 = add i32 %tmp.149, %tmp.150
  store i32 %tmp.151, ptr %tmp.139
  br label %while_cond.1

while_after.1:
  br label %if_merge.0

while_cond.2:
  %tmp.156 = load i32, ptr %tmp.155
  %tmp.157 = load i32, ptr @LIMIT
  %tmp.158 = icmp ule i32 %tmp.156, %tmp.157
  br i1 %tmp.158, label %while_body.2, label %while_after.2

while_body.2:
  %tmp.159 = load i32, ptr %tmp.155
  %tmp.160 = getelementptr [121 x i1], ptr %tmp.0, i32 0, i32 %tmp.159
  %tmp.161 = load i1, ptr %tmp.160
  br i1 %tmp.161, label %if_then.1, label %if_else.1

while_after.2:
  %tmp.168 = load i32, ptr %tmp.154
  store i32 %tmp.168, ptr %tmp.167
  br label %return.0

if_then.1:
  %tmp.162 = load i32, ptr %tmp.154
  %tmp.163 = load i32, ptr %tmp.155
  %tmp.164 = add i32 %tmp.162, %tmp.163
  store i32 %tmp.164, ptr %tmp.154
  br label %if_merge.1

if_else.1:
  br label %if_merge.1

if_merge.1:
  %tmp.165 = load i32, ptr %tmp.155
  %tmp.166 = add i32 %tmp.165, 1
  store i32 %tmp.166, ptr %tmp.155
  br label %while_cond.2

}

declare void @llvm.memcpy.p0.p0.i32(ptr, ptr, i32, i1) 

