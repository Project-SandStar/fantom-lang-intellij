@Serializable { simple = true }
class Foo
{
  @Opt { help = "Print usage help"; aliases = ["?"] }
  Bool help := false

  @Opt { help = "username to use"
         aliases = ["u"] }
  Str? user

  @sys::Transient @C { y = "foo"; z = [1, 2, 3] }
  Int t

  @NoDoc @Arg { help="Verbose" } Bool verbose

  @Js
  Void run() {}
}
