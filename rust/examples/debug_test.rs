use sb_book::{Book, BookConfig, BookError, Level};
use sb_core::{InstrumentSpec, Price, Qty};

fn main() {
    let spec = InstrumentSpec::new(2, 5, 10, 100, 0).unwrap();
    let config = BookConfig::new(1024, 100, 10).unwrap();
    
    let mut book = Book::new(config, spec.clone()).unwrap();
    
    let bid1 = Level::new(
        Price::from_units(1000).unwrap(),
        Qty::from_units(100).unwrap(),
    );
    let ask1 = Level::new(
        Price::from_units(1010).unwrap(),
        Qty::from_units(200).unwrap(),
    );
    
    println!("Bid price units: {}", bid1.price.units());
    println!("Ask price units: {}", ask1.price.units());
    println!("Bid >= Ask: {}", bid1.price >= ask1.price);
    
    let result = book.apply_snapshot(&[bid1], &[ask1]);
    println!("Result: {:?}", result);
}