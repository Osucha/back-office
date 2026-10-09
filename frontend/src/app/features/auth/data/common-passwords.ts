/**
 * A short excerpt of the most common passwords (SecLists 10k list, MIT License) for the live hint
 * under password fields. The backend checks the full list of 10 000 (ADR 0021) and has the final say;
 * this list only catches the obvious cases before the user submits.
 */
export const COMMON_PASSWORDS: ReadonlySet<string> = new Set(
  (
    'password passw0rd qwerty qwertyuiop qwertyui asdfgh asdfghjkl zxcvbn zxcvbnm qazwsx ' +
    'letmein welcome welcome1 admin administrator root login master secret changeme default ' +
    'iloveyou dragon monkey football baseball soccer hockey basketball tennis golf ' +
    'sunshine princess shadow superman batman starwars pokemon trustno1 whatever freedom ' +
    'computer internet access hello charlie michael jennifer jordan hunter ranger buster ' +
    'thomas tigger robert daniel jessica pepper joshua maggie silver william ashley amanda ' +
    'orange summer winter spring autumn flower forever lovely cookie cheese chocolate banana ' +
    'purple yellow diamond matrix falcon phoenix guitar mustang ferrari porsche mercedes ' +
    'killer thunder hammer ginger heather taylor austin merlin matthew martin chelsea ' +
    'liverpool arsenal manchester barcelona london chicago newyork dallas boston canada ' +
    'test tester testing temp guest user demo sample example company office business ' +
    'monday friday january december mother father family money lucky happy friend friends ' +
    'angel angels baby babygirl butterfly jasmine michelle nicole samantha elizabeth ' +
    'qwerty123 abc123 abcdef abcd1234 aaaaaa zzzzzz 1q2w3e4r 1qaz2wsx q1w2e3r4 ' +
    '123456 1234567 12345678 123456789 1234567890 12345678910 123456789012 111111 000000 ' +
    '123123 654321 987654321 112233 121212 666666 777777 888888 999999 11111111 ' +
    'backoffice keycloak spring angular relay'
  ).split(/\s+/),
);
