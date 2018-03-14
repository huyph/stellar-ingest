## Entities:
- films.csv = films and their properties
- persons.csv = people who worked in films, either as actors or other roles
- corps.csv = production companies

## Relationships
- producers.csv = companies (corps.csv) that produced films (film.csv)
- actors.csv = people (persons.csv) who acter in films (film.csv)
- staff.csv = people (persons.csv) who worked in films as non-actors (film.csv)

## Dataset generation

This example is the normal form of the imdb example:

*Example data and field numbering from ../imdb/imdb_small.csv*
```bash
id(1),filmtitle(2),year(3),cast1(4),cast2(5),cast3(6),crew1(7),crew2(8),crew3(9),genre(10),foreign(11),corporation(12)
18,Click,2006,Adam Sandler,Kate Beckinsale,Christopher Walken,Frank Coraci,Steve Koren,Mark O'Keefe,Romance,false,Lionsgate
19,Funny People,2009,Adam Sandler,Seth Rogen,Leslie Mann,Judd Apatow,Judd Apatow,Judd Apatow,Drama,false,20th Century Fox
20,Mr. Deeds,2002,Adam Sandler,Winona Ryder,John Turturro,Sid Ganis,Jack Giarraputo,Teddy Castellucci,Romance,false,Universal
```

These commands can be used to generate this dataset:

```bash
# Header OK.
cat ../imdb/imdb_small.csv| cut -d"," -f1,2,3,10,11 > films.csv
# Missing header: add 'stagename'
(echo "id,stagename"; cat ../imdb/imdb_small.csv| sed -n '2,$p'| cut -d"," -f4-9|sed 's/,/\n/g'|sort|uniq|cat -n|sed 's/\t/ /g; s/  */ /g; s/^ //g; s/ /,/') > persons.csv
# Header OK.
cat ../imdb/imdb_small.csv| cut -d"," -f12 > corps.csv

# Make header: 'corpname,filmid'
(echo "corpname,filmid"; cat ../imdb/imdb_small.csv| sed -n '2,$p'| cut -d"," -f12,1|awk 'BEGIN{FS=","} {print $2","$1}') > producers.csv

# Relate actors and films using ids instead of names.
cat ../imdb/imdb_small.csv| sed -n '2,$p'| cut -d"," -f1,4,5,6|awk 'BEGIN{FS=","} {printf("%s,%s\n%s,%s\n%s,%s\n",$1,$2,$1,$3,$1,$4);}'|sort -t"," -k2,2 > temp1
cat persons.csv| sed -n '2,$p'|sort -t"," -k2,2 > temp2
(echo "filmid,personid"; join -t"," -a1 -o 1.1,2.1 -1 2 -2 2 temp1 temp2) > actors.csv
rm temp1 temp2

# Relate actors and films using ids instead of names.
cat ../imdb/imdb_small.csv| sed -n '2,$p'| cut -d"," -f1,7,8,9|awk 'BEGIN{FS=","} {printf("%s,%s\n%s,%s\n%s,%s\n",$1,$2,$1,$3,$1,$4);}'|sort -t"," -k2,2 > temp1
cat persons.csv| sed -n '2,$p'|sort -t"," -k2,2 > temp2
(echo "filmid,personid"; join -t"," -a1 -o 1.1,2.1 -1 2 -2 2 temp1 temp2) > staff.csv
rm temp1 temp2
```
