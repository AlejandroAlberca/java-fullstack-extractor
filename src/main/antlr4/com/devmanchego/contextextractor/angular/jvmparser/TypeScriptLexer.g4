lexer grammar TypeScriptLexer;

// Keywords
AT          : '@' ;
CLASS       : 'class' ;
INTERFACE   : 'interface' ;
CONSTRUCTOR : 'constructor' ;
PRIVATE     : 'private' ;
PUBLIC      : 'public' ;
PROTECTED   : 'protected' ;
READONLY    : 'readonly' ;
EXTENDS     : 'extends' ;
IMPLEMENTS  : 'implements' ;
IMPORT      : 'import' ;
EXPORT      : 'export' ;
FROM        : 'from' ;
THIS        : 'this' ;
NEW         : 'new' ;
RETURN      : 'return' ;
CONST       : 'const' ;
LET         : 'let' ;
VAR         : 'var' ;

// Punctuation
LPAREN      : '(' ;
RPAREN      : ')' ;
LBRACE      : '{' ;
RBRACE      : '}' ;
LBRACKET    : '[' ;
RBRACKET    : ']' ;
SEMICOLON   : ';' ;
COLON       : ':' ;
COMMA       : ',' ;
DOT         : '.' ;
QUESTION    : '?' ;
EQUALS      : '=' ;
BANG        : '!' ;
PIPE        : '|' ;
AMP         : '&' ;
LANGLE      : '<' ;
RANGLE      : '>' ;
ARROW       : '=>' ;
ELLIPSIS    : '...' ;

// String literals — single and double quoted (no newlines)
STRING_DQ   : '"' ( ~["\\\r\n] | '\\' . )* '"' ;
STRING_SQ   : '\'' ( ~['\\\r\n] | '\\' . )* '\'' ;

// Template literal — captures the whole backtick string including ${...}
// This simplified version handles one level of nesting inside ${}
TEMPLATE_STRING
    : '`' ( ~[`\\$] | '\\' . | '$' ~[{] | '${' ( ~'}' | '{' ~'}'* '}' )* '}' )* '`'
    ;

// Numbers
NUMBER      : [0-9]+ ( '.' [0-9]+ )? ;

// Identifiers
ID          : [a-zA-Z_$] [a-zA-Z0-9_$]* ;

// Comments (skipped)
LINE_COMMENT  : '//' ~[\r\n]* -> skip ;
BLOCK_COMMENT : '/*' .*? '*/' -> skip ;

// Whitespace (skipped)
WS          : [ \t\r\n]+ -> skip ;

// Catch-all for any other character (operators, symbols we don't specifically handle)
OTHER       : . ;
