class A {
  Obj:Int reverse
  |OutStream out, Obj obj| writer
  |InStream in->Obj| reader
  |DocErr| onErr
  [Str:Str]? meta
}
