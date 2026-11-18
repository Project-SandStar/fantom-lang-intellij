package co.anbora.labs.fantom.lang.trio;

import com.intellij.lexer.FlexLexer;
import com.intellij.psi.tree.IElementType;
import static com.intellij.psi.TokenType.BAD_CHARACTER;
import static com.intellij.psi.TokenType.WHITE_SPACE;
import static co.anbora.labs.fantom.lang.trio.psi.TrioTypes.*;

// Trio lexer (Haystack records). The rules follow haxall haystack::TrioReader:
//   - a line that starts with `-` separates records, a line that starts with `//` is a comment;
//   - `name` is a marker tag, `name: value` has a value on the same line;
//   - `name:` with nothing after it takes the next indented lines as its value;
//   - `name: Zinc:` and `name: Trio:` take the next indented lines as a nested grid or record;
//   - `name: [` takes the next indented lines as list items.
// The first character of a value says how to read it, as in TrioReader.parseScalar:
// a quote, backtick, `@`, `^`, `{`, `[` or `Type(...)` is Zinc, a digit is a number, date
// or time when the whole value fits, and all other text is an unquoted string.
//
// The editor restarts the lexer at a token that starts in YYINITIAL. So a token is in
// YYINITIAL only when the text after it cannot change it or the tokens before it:
//   - the blank lines after a block are in AFTER_BLOCK, because an indent typed on the next
//     line makes that line a part of the block;
//   - a string with no closing quote is OPEN_STRING, and TrioLexer (Trio.kt) marks the state
//     of all tokens after it in the record, because a quote typed there closes the string.

%%

%{
  public _TrioLexer() {
    this(null);
  }

  private boolean isNewline(int p) {
    char c = zzBuffer.charAt(p);
    return c == '\n' || c == '\r';
  }

  private int lineEnd(int p) {
    while (p < zzEndRead && !isNewline(p)) p++;
    return p;
  }

  private int skipNewline(int p) {
    if (p >= zzEndRead) return p;
    char c = zzBuffer.charAt(p);
    if (c == '\r') return p + 1 < zzEndRead && zzBuffer.charAt(p + 1) == '\n' ? p + 2 : p + 1;
    return c == '\n' ? p + 1 : p;
  }

  /**
   * Start of the next line of the indented block after the newline at `nl`, or -1.
   * As in TrioReader.readIndentedText: blank lines are skipped, a line that starts with
   * a space belongs to the block, and so does a line of one character (a lone `]`).
   */
  private int nextBlockLine(int nl) {
    int p = skipNewline(nl);
    while (p < zzEndRead) {
      int lineStart = p;
      while (p < zzEndRead && (zzBuffer.charAt(p) == ' ' || zzBuffer.charAt(p) == '\t' || zzBuffer.charAt(p) == '\f')) p++;
      if (p >= zzEndRead) return -1;
      if (isNewline(p)) {
        p = skipNewline(p);
        continue;
      }
      if (p > lineStart) return lineStart;
      if (p + 1 < zzEndRead) return isNewline(p + 1) ? lineStart : -1;
      char c = zzBuffer.charAt(p);
      return c == ']' || c == '}' ? lineStart : -1;
    }
    return -1;
  }

  /** True for a line of three or more dashes and nothing else: the end of a record. */
  public static boolean isRecordEnd(CharSequence text, int start, int end) {
    int p = start;
    while (p < end && text.charAt(p) == '-') p++;
    if (p - start < 3) return false;
    while (p < end && (text.charAt(p) == ' ' || text.charAt(p) == '\t' || text.charAt(p) == '\f')) p++;
    return p == end;
  }

  /** True when the token has a record end line in it. A string does not go from one record to the next. */
  private boolean crossesRecordEnd() {
    for (int p = zzStartRead; p < zzMarkedPos; p++) {
      if (!isNewline(p)) continue;
      int line = skipNewline(p);
      if (line < zzMarkedPos && isRecordEnd(zzBuffer, line, lineEnd(line))) return true;
      p = line - 1;
    }
    return false;
  }

  /** End of the block that has a line at `from`: the end of its last line that is not blank. */
  private int blockEnd(int from) {
    int end = lineEnd(from);
    while (true) {
      int next = nextBlockLine(end);
      if (next < 0) return end;
      end = lineEnd(next);
    }
  }
%}

%public
%class _TrioLexer
%implements FlexLexer
%unicode
%function advance
%type IElementType
%eof{  return;
%eof}

// AFTER_NAME / AFTER_SRC: after a tag name.  VALUE / SRC_VAL: after the colon.
// KW_BLOCK: after `Zinc:` or `Trio:`.  BLOCK / SRC_BLOCK: before the text of an indented block.
// ZINC: a Zinc value on one line.  ZBLOCK: a Zinc list or dict over indented lines.
// JUNK: the rest of a line that is not a tag.
// AFTER_BLOCK: the blank lines after a block, or after `name:` with no block.
%xstate AFTER_NAME, AFTER_SRC, VALUE, SRC_VAL, KW_BLOCK, BLOCK, SRC_BLOCK, ZINC, ZBLOCK, JUNK, AFTER_BLOCK

NL = \r\n|\r|\n
WS = [ \t\f]+
NOT_WS = [^ \t\f\r\n]

NAME = [a-zA-Z_][a-zA-Z0-9_]*
ID_CHAR = [a-zA-Z0-9_:\-.~]

D = [0-9]
DATE = {D}{D}{D}{D}-{D}{D}-{D}{D}
SECONDS = :{D}{D}(\.{D}+)?
TIME = {D}{D}?:{D}{D}{SECONDS}?
TZ_OFFSET = Z|[+\-]{D}{D}:{D}{D}
// The timezone name starts with an upper case letter, as in HaystackTokenizer.
DATE_TIME = {DATE}T{D}{D}:{D}{D}{SECONDS}?{TZ_OFFSET}?([ ][A-Z][a-zA-Z0-9_+\-]*)?

UNIT = [a-zA-Z%$_\u0080-\uFFFF][a-zA-Z0-9%$/_\u0080-\uFFFF]*
DECIMAL = {D}[0-9_]*(\.{D}+)?([eE][+\-]?{D}+)?
HEX = 0x[0-9a-fA-F_]+
NUMBER = -?({HEX}|{DECIMAL}{UNIT}?)
NUMBER_WORD = NaN|INF|-INF

// A closed string may go over more than one line (SkySpark project exports write such strings),
// but not over a record end line. A string with no closing quote ends at the end of its line.
STRING = \"([^\"\\]|\\[^])*\"
OPEN_STRING = \"([^\"\\\r\n]|\\[^\r\n])*\\?
URI = `[^`\r\n]*`?
REF = @{ID_CHAR}+
SYMBOL = \^{ID_CHAR}+

// Text that is not Zinc: all of the line, without the spaces at its end.
RAW = [^ \t\f\r\n\"`@\^{\[]([^\r\n]*{NOT_WS})?
// Coord, Bin and XStr: `Type(...)` over the whole value.
TYPE_CALL = [A-Z][a-zA-Z0-9_]*\([^\r\n]*\)

%%

<YYINITIAL> {
  {WS}                 { return WHITE_SPACE; }
  {NL}                 { return WHITE_SPACE; }
  "//" [^\r\n]*        { return COMMENT; }
  "-" [^\r\n]*         { return SEPARATOR; }
  "src"                { yybegin(AFTER_SRC); return TAG_NAME; }
  {NAME}               { yybegin(AFTER_NAME); return TAG_NAME; }
  [^]                  { yypushback(yylength()); yybegin(JUNK); }
}

<AFTER_NAME, AFTER_SRC> {
  {WS}                 { return WHITE_SPACE; }
  {NL}                 { yybegin(YYINITIAL); return WHITE_SPACE; }
}
<AFTER_NAME> ":"       { yybegin(VALUE); return COLON; }
<AFTER_SRC> ":"        { yybegin(SRC_VAL); return COLON; }
<AFTER_NAME, AFTER_SRC> [^] { yypushback(yylength()); yybegin(JUNK); }

<JUNK> {
  [^\r\n]+             { return BAD_CHARACTER; }
  {NL}                 { yybegin(YYINITIAL); return WHITE_SPACE; }
}

<VALUE, SRC_VAL, KW_BLOCK> {WS} { return WHITE_SPACE; }

// `name:` and nothing more on the line: the value is the indented block, when there is one.
<VALUE, KW_BLOCK> {NL} { yybegin(nextBlockLine(zzStartRead) >= 0 ? BLOCK : AFTER_BLOCK); return WHITE_SPACE; }
<SRC_VAL> {NL}         { yybegin(nextBlockLine(zzStartRead) >= 0 ? SRC_BLOCK : AFTER_BLOCK); return WHITE_SPACE; }

<AFTER_BLOCK> {
  {WS}                 { return WHITE_SPACE; }
  {NL}                 { return WHITE_SPACE; }
  [^]                  { yypushback(yylength()); yybegin(YYINITIAL); }
}

<BLOCK, SRC_BLOCK> {
  {WS}                 { return WHITE_SPACE; }
  {NL}                 { return WHITE_SPACE; }
}
<BLOCK> {NOT_WS}       { zzMarkedPos = blockEnd(zzStartRead); yybegin(AFTER_BLOCK); return BLOCK_TEXT; }
<SRC_BLOCK> {NOT_WS}   { zzMarkedPos = blockEnd(zzStartRead); yybegin(AFTER_BLOCK); return SRC_VALUE; }

<KW_BLOCK> [^]         { yypushback(yylength()); yybegin(JUNK); }

// Axon source on the `src:` line. It goes on over indented lines, as the block form does.
<SRC_VAL> {
  \"                   { yypushback(1); yybegin(ZINC); }
  [^ \t\f\r\n\"]       { zzMarkedPos = blockEnd(zzStartRead); yybegin(AFTER_BLOCK); return SRC_VALUE; }
}

<VALUE> {
  // A lone `[` or `{`: the items are on the next indented lines.
  [\[{] {WS}? {NL}     { yypushback(yylength()); yybegin(ZBLOCK); }
  [\"`@\^{\[]          { yypushback(1); yybegin(ZINC); }
  {TYPE_CALL}          { yypushback(yylength()); yybegin(ZINC); }

  "Zinc:"              { yybegin(KW_BLOCK); return ZINC_KW; }
  "Trio:"              { yybegin(KW_BLOCK); return TRIO_KW; }
  "true" | "false" | "T" | "F"  { yybegin(YYINITIAL); return BOOL; }
  "NA"                 { yybegin(YYINITIAL); return NA; }
  "R"                  { yybegin(YYINITIAL); return REMOVE; }
  {NUMBER_WORD}        { yybegin(YYINITIAL); return NUMBER; }
  {DATE_TIME}          { yybegin(YYINITIAL); return DATE_TIME; }
  {DATE}               { yybegin(YYINITIAL); return DATE; }
  {TIME}               { yybegin(YYINITIAL); return TIME; }
  {NUMBER}             { yybegin(YYINITIAL); return NUMBER; }
  {RAW}                { yybegin(YYINITIAL); return UNQUOTED; }
}

<ZINC> {NL}            { yybegin(YYINITIAL); return WHITE_SPACE; }
<ZBLOCK> {
  {NL}                 { if (nextBlockLine(zzStartRead) < 0) yybegin(AFTER_BLOCK); return WHITE_SPACE; }
  "//" [^\r\n]*        { return COMMENT; }
}

<ZINC, ZBLOCK> {
  {WS}                 { return WHITE_SPACE; }
  "["                  { return LBRACKET; }
  "]"                  { return RBRACKET; }
  "{"                  { return LBRACE; }
  "}"                  { return RBRACE; }
  "("                  { return LPAREN; }
  ")"                  { return RPAREN; }
  ","                  { return COMMA; }
  ":"                  { return COLON; }

  {STRING}             { if (!crossesRecordEnd()) return STRING;
                         zzMarkedPos = lineEnd(zzStartRead); return OPEN_STRING; }
  {OPEN_STRING}        { return OPEN_STRING; }
  {URI}                { return URI; }
  {REF}                { return REF; }
  {SYMBOL}             { return SYMBOL; }
  {DATE_TIME}          { return DATE_TIME; }
  {DATE}               { return DATE; }
  {TIME}               { return TIME; }
  {NUMBER}             { return NUMBER; }
  {NUMBER_WORD}        { return NUMBER; }

  "true" | "false" | "T" | "F"  { return BOOL; }
  "NA"                 { return NA; }
  "N"                  { return NULL; }
  "M"                  { return MARKER; }
  "R"                  { return REMOVE; }
  "C" / "("            { return COORD_KW; }
  [A-Z][a-zA-Z0-9_]*   { return TYPE_NAME; }
  [a-z_][a-zA-Z0-9_]*  { return ID; }
  [^]                  { return BAD_CHARACTER; }
}
