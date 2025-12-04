declare void @printInt(i32) 

declare void @printlnInt(i32) 

declare i32 @getInt() 

define i32 @main() {
entry.0:
  %tmp.0 = alloca i32
  %tmp.1 = alloca i32
  %tmp.4 = alloca i32
  call void @fib(ptr %tmp.1, i32 10)
  %tmp.3 = load i32, ptr %tmp.1
  store i32 %tmp.3, ptr %tmp.0
  %tmp.5 = load i32, ptr %tmp.0
  store i32 %tmp.5, ptr %tmp.4
  br label %return.0
return.0:
  ret i32 0
}

define void @fib(ptr %ret_ptr, i32 %n) {
entry.0:
  %tmp.6 = alloca i32
  %tmp.10 = alloca i32
  %tmp.11 = alloca i32
  %tmp.12 = alloca i32
  %tmp.16 = alloca i32
  store i32 %n, ptr %tmp.6
  %tmp.7 = load i32, ptr %tmp.6
  %tmp.8 = icmp sle i32 %tmp.7, 1
  br i1 %tmp.8, label %if_then.0, label %if_else.0
return.0:
  ret void
if_then.0:
  %tmp.9 = load i32, ptr %tmp.6
  store i32 %tmp.9, ptr %ret_ptr
  br label %return.0
if_else.0:
  br label %if_merge.0
if_merge.0:
  store i32 0, ptr %tmp.10
  store i32 1, ptr %tmp.11
  store i32 2, ptr %tmp.12
  br label %while_cond.0
while_cond.0:
  %tmp.13 = load i32, ptr %tmp.12
  %tmp.14 = load i32, ptr %tmp.6
  %tmp.15 = icmp sle i32 %tmp.13, %tmp.14
  br i1 %tmp.15, label %while_body.0, label %while_after.0
while_body.0:
  %tmp.17 = load i32, ptr %tmp.10
  %tmp.18 = load i32, ptr %tmp.11
  %tmp.19 = add i32 %tmp.17, %tmp.18
  store i32 %tmp.19, ptr %tmp.16
  %tmp.20 = load i32, ptr %tmp.10
  %tmp.21 = load i32, ptr %tmp.11
  store i32 %tmp.21, ptr %tmp.10
  %tmp.22 = load i32, ptr %tmp.11
  %tmp.23 = load i32, ptr %tmp.16
  store i32 %tmp.23, ptr %tmp.11
  %tmp.24 = load i32, ptr %tmp.12
  %tmp.25 = add i32 %tmp.24, 1
  store i32 %tmp.25, ptr %tmp.12
  br label %while_cond.0
while_after.0:
  %tmp.26 = load i32, ptr %tmp.11
  store i32 %tmp.26, ptr %ret_ptr
  br label %return.0
}

